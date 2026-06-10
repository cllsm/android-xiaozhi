/**
 * 原生桥接模块
 *
 * 接收后端通过 WebSocket 下发的 native_call_request 事件，
 * 调用 Android 原生 API，并将结果回传给后端。
 *
 * 支持的方法：
 * - overlay_window: 显示/隐藏悬浮窗
 * - get_overlay_status: 获取悬浮窗状态
 * - take_photo: 拍照（Camera2 API / uni.chooseImage 降级）
 * - get_camera_status: 检查摄像头是否可用
 * - toast: 显示 Toast
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
  const plus = window.plus
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
  const plus = window.plus
  if (!plus) {
    throw new Error('plus 环境不可用')
  }
  return plus.android.runtimeMainActivity()
}

// ---------- 悬浮窗 ----------

/** 悬浮窗原生接口类名 */
const OVERLAY_CLASS = 'io.dcloud.PandoraEntry'

/** 当前悬浮窗状态 */
let _overlayVisible = false

/** 显示悬浮窗 */
async function handleOverlayWindow(args: Record<string, any>): Promise<any> {
  const show = args.show !== false
  const text = args.text || ''
  const x = args.x ?? 100
  const y = args.y ?? 100

  try {
    const activity = getMainActivity()
    // 调用 NativeBridge 的静态方法
    callAndroidStatic(
      'com.xiaozhi.native.NativeBridge',
      'showOverlayWindow',
      activity,
      show,
      text,
      x,
      y,
    )
    _overlayVisible = show
    return { visible: show }
  }
  catch (e: any) {
    console.warn('[NativeBridge] 悬浮窗调用失败 (非原生环境):', e.message)
    // H5 / 非 App 环境降级：仅更新本地状态
    _overlayVisible = show
    return { visible: show, fallback: true }
  }
}

/** 获取悬浮窗状态 */
function handleGetOverlayStatus(_args: Record<string, any>): any {
  return { visible: _overlayVisible }
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
          const plus = window.plus
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

methodHandlers.set('overlay_window', handleOverlayWindow)
methodHandlers.set('get_overlay_status', handleGetOverlayStatus)
methodHandlers.set('toast', handleToast)
methodHandlers.set('take_photo', handleTakePhoto)
methodHandlers.set('get_camera_status', handleGetCameraStatus)

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
