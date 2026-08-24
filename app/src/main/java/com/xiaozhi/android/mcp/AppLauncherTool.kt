package com.xiaozhi.android.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

class AppLauncherTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.application.launch",
        description = "Launch an Android application by readable label or package name.",
        properties = JSONObject().put(
            "app_name",
            JSONObject().put("type", "string")
        ),
        required = listOf("app_name")
    )

    override fun call(arguments: JSONObject): Any? {
        val appName = arguments.optString("app_name")
        if (appName.isBlank()) return false

        val intent = if (appName.contains('.')) {
            context.packageManager.getLaunchIntentForPackage(appName)
        } else {
            findLaunchIntent(appName)
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun findLaunchIntent(appName: String): Intent? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = context.packageManager.queryIntentActivities(
            query,
            PackageManager.MATCH_ALL
        )
        val target = resolveInfos.firstOrNull { info ->
            info.loadLabel(context.packageManager).toString().contains(
                appName,
                ignoreCase = true
            )
        } ?: resolveInfos.firstOrNull { info ->
            info.activityInfo.packageName.contains(appName, ignoreCase = true)
        } ?: return null
        return context.packageManager.getLaunchIntentForPackage(target.activityInfo.packageName)
    }
}

class InstalledAppsTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.application.scan_installed",
        description = "List launchable applications visible to this app."
    )

    override fun call(arguments: JSONObject): Any? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(
            query,
            PackageManager.MATCH_ALL
        ).map { info ->
            JSONObject()
                .put(
                    "name",
                    info.loadLabel(context.packageManager).toString()
                )
                .put("package", info.activityInfo.packageName)
        }
        return JSONObject()
            .put("success", true)
            .put("count", apps.size)
            .put("apps", JSONArray(apps))
    }
}
