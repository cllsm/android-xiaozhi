/**
 * 原生桥接模块
 *
 * 接收后端通过 WebSocket 下发的 native_call_request 事件，
 * 调用 Android 原生 API，并将结果回传给后端。
 *
 * 支持的方法：
 * - take_photo: 拍照（Camera2 API / uni.chooseImage 降级）
 * - get_camera_status: 检查摄像头是否可用
 * - toast: 显示 Toast
 *
 * 系统悬浮窗（导出函数，供 FloatingBall 组件调用）：
 * - checkOverlayPermission(): 检查并请求 SYSTEM_ALERT_WINDOW 权限
 * - createSystemOverlay(state): 创建系统级悬浮球
 * - updateSystemOverlay(state): 更新悬浮球状态颜色
 * - removeSystemOverlay(): 移除系统悬浮球
 */

import { backendService } from '@/api/backend'

/** 原生调用的方法处理器类型 */
type NativeMethodHandler = (args: Record<string, any>) => Promise<any> | any

/** 方法名 → 处理器映射 */
const methodHandlers: Map<string, NativeMethodHandler> = new Map()

// ========== 原生方法实现 ==========

/**
 * 调用 Android 原生方法（通过 plus.android）
 * @param className Android 类全名
 * @param methodName 静态方法名
 * @param args 参数列表
 */
function callAndroidStatic(className: string, methodName: string, ...args: any[]): any {
  // @ts-expect-error uni-app App 环境全局对象
  if (!plus) {
    throw new Error('plus 环境不可用，请确认运行在 App 端')
  }
  const cls = plus.android.importClass(className)
  if (!cls) {
    throw new Error(`无法导入 Android 类: ${className}`)
  }
  return cls[methodName](...args)
}

/**
 * 获取 Android 主 Activity 实例
 */
function getMainActivity(): any {
  // @ts-expect-error uni-app App 环境全局对象
  if (!plus) {
    throw new Error('plus 环境不可用')
  }
  return plus.android.runtimeMainActivity()
}

// ---------- Toast ----------

/** 显示 Android Toast */
async function handleToast(args: Record<string, any>): Promise<any> {
  const message = args.message || ''
  const duration = args.duration || 'short'

  try {
    const activity = getMainActivity()
    callAndroidStatic(
      'android.widget.Toast',
      duration === 'long' ? 'makeTextLong' : 'makeText',
      activity,
      message,
    )
    return { shown: true }
  }
  catch (_e) {
    // 降级到 uni.showToast
    uni.showToast({ title: message, icon: 'none', duration: duration === 'long' ? 3500 : 1500 })
    return { shown: true, fallback: true }
  }
}

// ---------- 摄像头 ----------

/** 拍照 */
async function handleTakePhoto(args: Record<string, any>): Promise<any> {
  // 尝试 Android 原生 Camera2 API
  try {
    const activity = getMainActivity()
    const result = callAndroidStatic(
      'com.xiaozhi.native.NativeBridge',
      'takePhoto',
      activity,
      args.quality || 85,
    )
    if (result && result.image_data) {
      return result
    }
  }
  catch (e: any) {
    console.warn('[NativeBridge] 原生拍照不可用，降级到 uni API:', e.message)
  }

  // 降级：使用 uni.chooseImage（H5 / 非 App 环境）
  return new Promise((resolve, reject) => {
    uni.chooseImage({
      count: 1,
      sourceType: ['camera'],
      sizeType: ['compressed'],
      success: (res: any) => {
        const tempPath = res.tempFilePaths[0]
        // 读取文件并转为 base64
        // #ifdef H5
        // H5 环境：通过 canvas 压缩转 base64
        const img = new Image()
        img.onload = () => {
          const canvas = document.createElement('canvas')
          const maxSize = 1280
          let w = img.width
          let h = img.height
          if (w > maxSize || h > maxSize) {
            const ratio = Math.min(maxSize / w, maxSize / h)
            w = Math.round(w * ratio)
            h = Math.round(h * ratio)
          }
          canvas.width = w
          canvas.height = h
          const ctx = canvas.getContext('2d')!
          ctx.drawImage(img, 0, 0, w, h)
          const dataUrl = canvas.toDataURL('image/jpeg', 0.85)
          const base64 = dataUrl.split(',')[1]
          resolve({ image_data: base64, width: w, height: h, fallback: true })
        }
        img.onerror = () => reject(new Error('图片加载失败'))
        img.src = tempPath
        // #endif
        // #ifndef H5
        // App 环境：通过 plus.io 读取文件转 base64
        try {
          // @ts-expect-error plus 全局对象
          // @ts-expect-error uni-app App 环境全局对象
          plus.io.resolveLocalFileSystemURL(tempPath, (entry: any) => {
            entry.file((file: any) => {
              const reader = new plus.io.FileReader()
              reader.onloadend = (e: any) => {
                const dataUrl = e.target.result
                const base64 = dataUrl.split(',')[1]
                resolve({ image_data: base64, fallback: true })
              }
              reader.onerror = () => reject(new Error('文件读取失败'))
              reader.readAsDataURL(file)
            })
          }, () => reject(new Error('文件解析失败')))
        }
        catch (_e) {
          // 最终兜底：返回路径，让后端处理
          resolve({ image_path: tempPath, fallback: true })
        }
        // #endif
      },
      fail: (err: any) => reject(new Error(`拍照失败: ${err.errMsg || '用户取消'}`)),
    })
  })
}

/** 获取摄像头状态 */
async function handleGetCameraStatus(_args: Record<string, any>): Promise<any> {
  try {
    const activity = getMainActivity()
    const hasCamera = callAndroidStatic(
      'com.xiaozhi.native.NativeBridge',
      'hasCamera',
      activity,
    )
    return { available: !!hasCamera }
  }
  catch (_e) {
    return { available: true, fallback: true }
  }
}

// ========== 注册方法 ==========

methodHandlers.set('toast', handleToast)
methodHandlers.set('take_photo', handleTakePhoto)
methodHandlers.set('get_camera_status', handleGetCameraStatus)

// ========== 系统悬浮窗（后台最小化时显示） ==========

let _systemOverlayView: any = null
let _systemWindowManager: any = null
let _systemLayoutParams: any = null

/**
 * 检查并请求 Android 悬浮窗权限 (SYSTEM_ALERT_WINDOW)
 * @returns 是否已授予权限
 */
export function checkOverlayPermission(): boolean {
  try {
    // @ts-expect-error uni-app plus
    if (typeof plus === 'undefined') return false
    const Settings = plus.android.importClass('android.provider.Settings')
    const activity = plus.android.runtimeMainActivity()
    const canDraw = Settings.canDrawOverlays(activity)
    console.log('[NativeBridge] 悬浮窗权限检查: canDrawOverlays=' + canDraw)
    if (!canDraw) {
      // 跳转到悬浮窗权限设置页
      const Intent = plus.android.importClass('android.content.Intent')
      const Uri = plus.android.importClass('android.net.Uri')
      const intent = new Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse('package:' + activity.getPackageName()),
      )
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      activity.startActivity(intent)
      uni.showToast({ title: '请授予悬浮窗权限', icon: 'none' })
      return false
    }
    return true
  }
  catch (e) {
    console.warn('[NativeBridge] 检查悬浮窗权限失败:', e)
    return false
  }
}

/**
 * 创建 Android 系统级悬浮球
 * App 进入后台时调用，在屏幕边缘显示一个小圆球
 * @param state 当前设备状态 'IDLE' | 'LISTENING' | 'SPEAKING' | 'CONNECTING'
 */
export function createSystemOverlay(state: string = 'IDLE'): void {
  // #ifdef APP-PLUS
  try {
    // @ts-expect-error uni-app plus
    if (typeof plus === 'undefined') {
      console.warn('[NativeBridge] plus 不可用，无法创建系统悬浮球')
      return
    }
    if (_systemOverlayView) {
      console.log('[NativeBridge] 系统悬浮球已存在，仅更新状态')
      updateSystemOverlay(state)
      return
    }

    const activity = plus.android.runtimeMainActivity()
    if (!activity) {
      console.error('[NativeBridge] 无法获取 MainActivity')
      return
    }

    console.log('[NativeBridge] 开始创建系统悬浮球, state=' + state)

    // ★ 用 uni API 获取屏幕尺寸（逻辑像素），再转物理像素
    const sysInfo = uni.getSystemInfoSync()
    const density = sysInfo.pixelRatio || 3
    const screenPxW = Math.round(sysInfo.screenWidth * density)
    const screenPxH = Math.round(sysInfo.screenHeight * density)
    // 悬浮球大小：48dp
    const sizePx = Math.round(48 * density)
    console.log('[NativeBridge] 屏幕物理像素: ' + screenPxW + 'x' + screenPxH + ', density=' + density + ', sizePx=' + sizePx)

    // 导入 Android 类
    const FrameLayout = plus.android.importClass('android.widget.FrameLayout')
    const TextView = plus.android.importClass('android.widget.TextView')
    const Gravity = plus.android.importClass('android.view.Gravity')
    const Color = plus.android.importClass('android.graphics.Color')
    const GradientDrawable = plus.android.importClass('android.graphics.drawable.GradientDrawable')
    const View = plus.android.importClass('android.view.View')
    const Intent = plus.android.importClass('android.content.Intent')

    // 获取 WindowManager
    const wm = plus.android.invoke(activity, 'getSystemService', 'window')
    _systemWindowManager = wm

    // 创建 LayoutParams
    const LayoutParams = plus.android.importClass('android.view.WindowManager$LayoutParams')
    const lp = new LayoutParams()
    plus.android.setAttribute(lp, 'type', 2038)              // TYPE_APPLICATION_OVERLAY
    plus.android.setAttribute(lp, 'format', -3)              // TRANSLUCENT
    // ★ 只用 FLAG_NOT_FOCUSABLE(8)，去掉 WATCH_OUTSIDE_TOUCH 和 LAYOUT_NO_LIMITS
    // 这样窗口才能接收到自身区域的触摸事件
    plus.android.setAttribute(lp, 'flags', 8)
    plus.android.setAttribute(lp, 'width', sizePx)
    plus.android.setAttribute(lp, 'height', sizePx)
    plus.android.setAttribute(lp, 'gravity', 8388659)        // TOP | START
    plus.android.setAttribute(lp, 'x', screenPxW - sizePx - Math.round(16 * density))
    plus.android.setAttribute(lp, 'y', Math.round(screenPxH * 0.45))
    _systemLayoutParams = lp

    // 创建 FrameLayout 容器
    const container = new FrameLayout(activity)

    // 创建圆形背景
    const bgColor = _getStateColor(state, Color)
    const shape = new GradientDrawable()
    shape.setShape(GradientDrawable.OVAL)
    shape.setColor(bgColor)

    // 创建 TextView 作为悬浮球（支持文字+背景，最可靠）
    const ballView = new TextView(activity)
    ballView.setText('💬')
    ballView.setTextSize(20)
    ballView.setTextColor(Color.WHITE)
    ballView.setGravity(Gravity.CENTER)
    ballView.setBackgroundDrawable(shape)
    ballView.setClickable(true)

    const flp = new FrameLayout.LayoutParams(sizePx, sizePx)
    ballView.setLayoutParams(flp)
    container.addView(ballView)

    // 闭包捕获参数
    const _screenWidth = screenPxW
    const _sizePx = sizePx

    // ★ 用 OnTouchListener 替代 OnClickListener（plus.android 更可靠）
    // 用数值常量避免类常量代理问题
    let touchDownX = 0
    let touchDownY = 0
    let touchDownTime = 0
    // ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2
    ballView.setOnTouchListener(new View.OnTouchListener({
      onTouch(_v: any, event: any) {
        try {
          const action = plus.android.invoke(event, 'getAction')
          if (action === 0) {
            // ACTION_DOWN
            touchDownX = plus.android.invoke(event, 'getRawX')
            touchDownY = plus.android.invoke(event, 'getRawY')
            touchDownTime = Date.now()
            return true
          }
          if (action === 1) {
            // ACTION_UP — 判断是点击还是拖动结束
            const upX = plus.android.invoke(event, 'getRawX')
            const upY = plus.android.invoke(event, 'getRawY')
            const dt = Date.now() - touchDownTime
            const dist = Math.abs(upX - touchDownX) + Math.abs(upY - touchDownY)

            if (dist < 20 && dt < 500) {
              // ★ 点击 → 唤起 App
              console.log('[NativeBridge] 系统悬浮球被点击，唤起 App')
              const intent = new Intent(activity, activity.getClass())
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
              activity.startActivity(intent)
            }
            else {
              // 拖动结束 → 吸附到最近边缘
              const curX = plus.android.getAttribute(lp, 'x') || 0
              const snapX = curX < _screenWidth / 2 ? 10 : _screenWidth - _sizePx - 10
              plus.android.setAttribute(lp, 'x', snapX)
              try { plus.android.invoke(wm, 'updateViewLayout', container, lp) } catch (_e) {}
            }
            return true
          }
          if (action === 2) {
            // ACTION_MOVE — 拖动
            const curX = plus.android.invoke(event, 'getRawX')
            const curY = plus.android.invoke(event, 'getRawY')
            const dx = curX - touchDownX
            const dy = curY - touchDownY
            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
              const oldX = plus.android.getAttribute(lp, 'x') || 0
              const oldY = plus.android.getAttribute(lp, 'y') || 0
              plus.android.setAttribute(lp, 'x', Math.round(oldX + dx))
              plus.android.setAttribute(lp, 'y', Math.round(oldY + dy))
              try { plus.android.invoke(wm, 'updateViewLayout', container, lp) } catch (_e) {}
              touchDownX = curX
              touchDownY = curY
            }
            return true
          }
        }
        catch (touchErr) {
          console.warn('[NativeBridge] 触摸事件异常:', touchErr)
        }
        return true
      },
    }))

    // 添加到 WindowManager
    plus.android.invoke(wm, 'addView', container, lp)
    _systemOverlayView = container
    _systemOverlayView._ballView = ballView

    console.log('[NativeBridge] ✓ 系统悬浮球已创建, size=' + sizePx + 'px')
  }
  catch (e: any) {
    console.error('[NativeBridge] 创建系统悬浮球失败:', e?.message || e)
  }
  // #endif
}

/** 根据状态返回颜色 */
function _getStateColor(state: string, Color: any): number {
  switch (state) {
    case 'LISTENING': return Color.parseColor('#66bb6a')  // 绿色
    case 'SPEAKING': return Color.parseColor('#ffa726')   // 橙色
    case 'CONNECTING': return Color.parseColor('#9e9e9e') // 灰色
    default: return Color.parseColor('#4fc3f7')           // 蓝色
  }
}

/**
 * 更新系统悬浮球状态（颜色 + emoji）
 */
export function updateSystemOverlay(state: string): void {
  // #ifdef APP-PLUS
  try {
    if (!_systemOverlayView || !_systemOverlayView._ballView) return
    // @ts-expect-error uni-app plus
    if (typeof plus === 'undefined') return

    const Color = plus.android.importClass('android.graphics.Color')
    const GradientDrawable = plus.android.importClass('android.graphics.drawable.GradientDrawable')
    const bgColor = _getStateColor(state, Color)
    const shape = new GradientDrawable()
    shape.setShape(GradientDrawable.OVAL)
    shape.setColor(bgColor)
    _systemOverlayView._ballView.setBackgroundDrawable(shape)

    // 更新 emoji
    const emoji = _getStateEmoji(state)
    _systemOverlayView._ballView.setText(emoji)

    console.log('[NativeBridge] 系统悬浮球状态已更新: ' + state)
  }
  catch (e) {
    console.warn('[NativeBridge] 更新系统悬浮球失败:', e)
  }
  // #endif
}

/** 根据状态返回 emoji */
function _getStateEmoji(state: string): string {
  switch (state) {
    case 'LISTENING': return '🎙'
    case 'SPEAKING': return '🔊'
    case 'CONNECTING': return '⏳'
    default: return '💬'
  }
}

/**
 * 移除系统悬浮球
 */
export function removeSystemOverlay(): void {
  // #ifdef APP-PLUS
  try {
    if (_systemOverlayView && _systemWindowManager) {
      // @ts-expect-error uni-app plus
      plus.android.invoke(_systemWindowManager, 'removeView', _systemOverlayView)
      console.log('[NativeBridge] 系统悬浮球已移除')
    }
  }
  catch (e) {
    console.warn('[NativeBridge] 移除系统悬浮球失败:', e)
  }
  finally {
    _systemOverlayView = null
    _systemWindowManager = null
    _systemLayoutParams = null
  }
  // #endif
}

// ========== 事件监听 ==========

/**
 * 处理后端下发的 native_call_request 事件
 * @param data { request_id, method, args }
 */
async function handleNativeCallRequest(data: any): Promise<void> {
  const { request_id, method, args } = data
  if (!request_id || !method) {
    console.warn('[NativeBridge] 无效的 native_call_request:', data)
    return
  }

  console.log(`[NativeBridge] 收到原生调用请求: method=${method}, request_id=${request_id}`)

  let result: any
  try {
    const handler = methodHandlers.get(method)
    if (!handler) {
      throw new Error(`未注册的原生方法: ${method}`)
    }
    result = await handler(args || {})
  }
  catch (e: any) {
    console.error(`[NativeBridge] 原生调用失败: method=${method}`, e)
    result = { error: e.message || String(e) }
  }

  // 将结果回传给后端
  try {
    await backendService.sendCommand('native_call_response', {
      request_id,
      result,
    })
    console.log(`[NativeBridge] 已回传结果: request_id=${request_id}`)
  }
  catch (e: any) {
    console.error(`[NativeBridge] 回传结果失败:`, e)
  }
}

/**
 * 初始化原生桥接：注册事件监听
 * 应在 app store 的 connectBackend() 中调用
 */
export function initNativeBridge(): void {
  backendService.on('native_call_request', handleNativeCallRequest)
  console.log('[NativeBridge] 已注册 native_call_request 事件监听')
}

/**
 * 手动调用原生方法（前端组件也可直接使用）
 * @param method 方法名
 * @param args 参数
 * @returns 调用结果
 */
export async function callNative(method: string, args: Record<string, any> = {}): Promise<any> {
  const handler = methodHandlers.get(method)
  if (!handler) {
    throw new Error(`未注册的原生方法: ${method}`)
  }
  return handler(args)
}
