package com.xiaozhi.android.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

class AppLauncherTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.application.launch",
        description = "打开应用；传应用名或包名，不要带动作词。",
        properties = JSONObject().put(
            "app_name",
            JSONObject().put("type", "string")
        ),
        required = listOf("app_name")
    )

    override fun call(arguments: JSONObject): Any? {
        val appName = arguments.optString("app_name")
        return launch(appName)
    }

    fun launch(appName: String): JSONObject {
        if (appName.isBlank()) return failure("请告诉我要打开哪个应用")

        val target = AppNameMatcher.bestMatch(launcherCandidates(), appName)
            ?: return failure("没有找到应用“$appName”")
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return failure("“${target.label}”当前不能直接打开")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching {
            context.startActivity(intent)
            success(target)
        }.getOrElse {
            failure("系统暂时无法打开“${target.label}”，请稍后再试")
        }
    }

    private fun launcherCandidates(): List<LauncherAppCandidate> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager
            .queryIntentActivities(query, PackageManager.MATCH_ALL)
            .asSequence()
            .map { info ->
                LauncherAppCandidate(
                    label = info.loadLabel(context.packageManager).toString(),
                    packageName = info.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    private fun success(target: LauncherAppCandidate): JSONObject {
        return JSONObject()
            .put("success", true)
            .put("message", "已打开${target.label}")
            .put("app", target.label)
            .put("package", target.packageName)
    }

    private fun failure(message: String): JSONObject {
        return JSONObject()
            .put("success", false)
            .put("message", message)
    }

    companion object {
        fun launch(context: Context, appName: String): JSONObject {
            return AppLauncherTool(context).launch(appName)
        }

        fun isLaunchRequest(text: String): Boolean {
            return AppNameMatcher.isDirectLaunchCommand(text)
        }
    }
}

class InstalledAppsTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.application.scan_installed",
        description = "List launchable applications visible to this app."
    )

    override fun call(arguments: JSONObject): Any? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager
            .queryIntentActivities(query, PackageManager.MATCH_ALL)
            .asSequence()
            .map { info ->
                LauncherAppCandidate(
                    label = info.loadLabel(context.packageManager).toString(),
                    packageName = info.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
            .map { app ->
                JSONObject()
                    .put("name", app.label)
                    .put("package", app.packageName)
            }
            .toList()
        return JSONObject()
            .put("success", true)
            .put("count", apps.size)
            .put("apps", JSONArray(apps))
    }
}
