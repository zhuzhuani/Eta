package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun supportedLocalesSelectExpectedResourcesAndOthersFallBackToEnglish() {
        assertEquals("Settings", localizedString("en-US", R.string.route_settings))
        assertEquals("设置", localizedString("zh-CN", R.string.route_settings))
        assertEquals("设置", localizedString("zh-SG", R.string.route_settings))
        assertEquals("設定", localizedString("zh-TW", R.string.route_settings))
        assertEquals("設定", localizedString("zh-HK", R.string.route_settings))
        assertEquals("昨日", localizedString("zh-TW", R.string.time_yesterday))
        assertEquals("Settings", localizedString("fr-FR", R.string.route_settings))
        assertEquals("Appearance & Theme", localizedString("en-US", R.string.appearance_title))
        assertEquals("外观与主题", localizedString("zh-CN", R.string.appearance_title))
        assertEquals("外觀與主題", localizedString("zh-TW", R.string.appearance_title))
        assertEquals("Appearance & Theme", localizedString("fr-FR", R.string.appearance_title))
        assertEquals("1 model", localizedQuantity("en-US", R.plurals.provider_models_count, 1))
        assertEquals("2 models", localizedQuantity("en-US", R.plurals.provider_models_count, 2))
    }

    @Test
    fun supplementalUiResourcesFollowLocaleWhileTechnicalTermsStayEnglish() {
        assertEquals("Current device", localizedString("en-US", R.string.capability_current_device))
        assertEquals("当前设备", localizedString("zh-CN", R.string.capability_current_device))
        assertEquals("目前裝置", localizedString("zh-TW", R.string.capability_current_device))
        assertEquals("Current device", localizedString("fr-FR", R.string.capability_current_device))
        assertEquals("Eta is running", localizedString("en-US", R.string.execution_title))
        assertEquals("Eta 正在运行", localizedString("zh-CN", R.string.execution_title))
        assertEquals("Eta 正在執行", localizedString("zh-TW", R.string.execution_title))
        assertEquals("Environment configuration", localizedString("en-US", R.string.linux_environment_configuration))
        assertEquals("环境配置", localizedString("zh-CN", R.string.linux_environment_configuration))
        assertEquals("環境設定", localizedString("zh-TW", R.string.linux_environment_configuration))
        assertEquals("View description", localizedString("en-US", R.string.ui_view_description))
        assertEquals("查看说明", localizedString("zh-CN", R.string.ui_view_description))
        assertEquals("查看說明", localizedString("zh-TW", R.string.ui_view_description))
        assertEquals("Retrying model request", localizedString("en-US", R.string.system_notice_model_retry))
        assertEquals("模型请求重试", localizedString("zh-CN", R.string.system_notice_model_retry))
        assertEquals("模型請求重試", localizedString("zh-TW", R.string.system_notice_model_retry))
        assertEquals(
            "1 task is running. Return to Eta to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 1),
        )
        assertEquals(
            "2 tasks are running. Return to Eta to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 2),
        )
        assertEquals(
            "已导入 1 个文件，其余文件无法读取或超过 32 MiB",
            localizedQuantity("zh-CN", R.plurals.capability_workspace_partial_import, 1),
        )
        assertEquals(
            "已匯入 2 個檔案，其餘檔案無法讀取或超過 32 MiB",
            localizedQuantity("zh-TW", R.plurals.capability_workspace_partial_import, 2),
        )
        for (locale in listOf("en-US", "zh-CN", "zh-TW", "fr-FR")) {
            assertEquals("Skills", localizedString(locale, R.string.route_skills))
            assertEquals("API Key", localizedString(locale, R.string.speech_api_key))
        }
    }

    @Test
    fun scheduledTaskUiResourcesAreLocalizedInAllSupportedLocales() {
        assertEquals("Scheduled tasks", localizedString("en-US", R.string.automation_title))
        assertEquals("自动任务", localizedString("zh-CN", R.string.automation_title))
        assertEquals("自動任務", localizedString("zh-TW", R.string.automation_title))
        assertEquals("Run now", localizedString("en-US", R.string.automation_run))
        assertEquals("立即运行", localizedString("zh-CN", R.string.automation_run))
        assertEquals("立即運行", localizedString("zh-TW", R.string.automation_run))
        assertEquals("Triggers", localizedString("en-US", R.string.automation_triggers_title))
        assertEquals("触发器", localizedString("zh-CN", R.string.automation_triggers_title))
        assertEquals("觸發器", localizedString("zh-TW", R.string.automation_triggers_title))
        assertEquals("Available task triggers", localizedString("en-US", R.string.automation_tool_triggers))
        assertEquals("查看可用触发器", localizedString("zh-CN", R.string.automation_tool_triggers))
        assertEquals("查看可用觸發器", localizedString("zh-TW", R.string.automation_tool_triggers))
        assertEquals("Monitoring task triggers", localizedString("en-US", R.string.automation_monitor_title))
        assertEquals("正在监控任务触发器", localizedString("zh-CN", R.string.automation_monitor_title))
        assertEquals("正在監控任務觸發器", localizedString("zh-TW", R.string.automation_monitor_title))
        assertEquals("Export diagnostic ZIP", localizedString("en-US", R.string.automation_export_diagnostics))
        assertEquals("导出诊断包", localizedString("zh-CN", R.string.automation_export_diagnostics))
        assertEquals("匯出診斷套件", localizedString("zh-TW", R.string.automation_export_diagnostics))
        assertEquals("Export cancelled.", localizedString("en-US", R.string.automation_export_cancelled))
        assertEquals("已取消导出。", localizedString("zh-CN", R.string.automation_export_cancelled))
        assertEquals("已取消匯出。", localizedString("zh-TW", R.string.automation_export_cancelled))
        assertEquals("Choose where to save the diagnostic ZIP…", localizedString("en-US", R.string.automation_export_picking))
        assertEquals("请选择诊断包的保存位置…", localizedString("zh-CN", R.string.automation_export_picking))
        assertEquals("請選擇診斷套件的儲存位置…", localizedString("zh-TW", R.string.automation_export_picking))
        assertEquals("Exporting diagnostic ZIP…", localizedString("en-US", R.string.automation_export_running))
        assertEquals("正在导出诊断包…", localizedString("zh-CN", R.string.automation_export_running))
        assertEquals("正在匯出診斷套件…", localizedString("zh-TW", R.string.automation_export_running))
        assertEquals("Diagnostic ZIP saved.", localizedString("en-US", R.string.automation_export_succeeded))
        assertEquals("诊断包已保存。", localizedString("zh-CN", R.string.automation_export_succeeded))
        assertEquals("診斷套件已儲存。", localizedString("zh-TW", R.string.automation_export_succeeded))
        assertEquals("Couldn’t export the diagnostic ZIP. Check available storage and try again.", localizedString("en-US", R.string.automation_export_failed))
        assertEquals("无法导出诊断包。请检查可用存储空间后重试。", localizedString("zh-CN", R.string.automation_export_failed))
        assertEquals("無法匯出診斷套件。請檢查可用儲存空間後重試。", localizedString("zh-TW", R.string.automation_export_failed))
        assertTrue(localizedString("en-US", R.string.automation_export_privacy).contains("not uploaded"))
        assertTrue(localizedString("zh-CN", R.string.automation_export_privacy).contains("不会上传"))
        assertTrue(localizedString("zh-TW", R.string.automation_export_privacy).contains("不會上傳"))
        assertEquals(
            "Capture full task diagnostics",
            localizedString("en-US", R.string.automation_export_full_capture_enabled),
        )
        assertEquals(
            "采集此任务的完整诊断",
            localizedString("zh-CN", R.string.automation_export_full_capture_enabled),
        )
        assertEquals(
            "採集此任務的完整診斷",
            localizedString("zh-TW", R.string.automation_export_full_capture_enabled),
        )
        assertTrue(
            localizedString("en-US", R.string.automation_export_full_capture_summary)
                .contains("On by default"),
        )
        assertTrue(
            localizedString("zh-CN", R.string.automation_export_full_capture_summary)
                .contains("默认开启"),
        )
        assertTrue(
            localizedString("zh-TW", R.string.automation_export_full_capture_summary)
                .contains("預設開啟"),
        )
        assertTrue(
            localizedString("en-US", R.string.automation_export_full_capture_summary)
                .contains("Capture mode is fixed when each run is claimed"),
        )
        assertTrue(
            localizedString("en-US", R.string.automation_export_full_capture_summary)
                .contains("disabling it immediately stops that run’s capture"),
        )
        assertTrue(
            localizedString("zh-CN", R.string.automation_export_full_capture_summary)
                .contains("每次运行在认领时固定采集模式"),
        )
        assertTrue(
            localizedString("zh-CN", R.string.automation_export_full_capture_summary)
                .contains("关闭会立即停止当前采集"),
        )
        assertTrue(
            localizedString("zh-TW", R.string.automation_export_full_capture_summary)
                .contains("每次執行在認領時固定採集模式"),
        )
        assertTrue(
            localizedString("zh-TW", R.string.automation_export_full_capture_summary)
                .contains("關閉會立即停止目前採集"),
        )
        assertTrue(
            localizedString("en-US", R.string.automation_export_full_capture_warning)
                .contains("cannot guarantee every secret is removed"),
        )
        assertTrue(
            localizedString("zh-CN", R.string.automation_export_full_capture_warning)
                .contains("无法保证移除所有秘密"),
        )
        assertTrue(
            localizedString("zh-TW", R.string.automation_export_full_capture_warning)
                .contains("無法保證移除所有秘密"),
        )
        assertTrue(
            localizedString("en-US", R.string.automation_export_full_capture_warning)
                .contains("Nothing is uploaded automatically"),
        )
        assertTrue(
            localizedString("zh-CN", R.string.automation_export_full_capture_warning)
                .contains("不会自动上传"),
        )
        assertTrue(
            localizedString("zh-TW", R.string.automation_export_full_capture_warning)
                .contains("不會自動上傳"),
        )
        assertEquals(
            "Loading this task’s full-diagnostics setting…",
            localizedString("en-US", R.string.automation_export_full_capture_loading),
        )
        assertEquals(
            "正在读取此任务的完整诊断设置…",
            localizedString("zh-CN", R.string.automation_export_full_capture_loading),
        )
        assertEquals(
            "正在讀取此任務的完整診斷設定…",
            localizedString("zh-TW", R.string.automation_export_full_capture_loading),
        )
        assertEquals(
            "Couldn’t read this task’s full-diagnostics setting. Retry by reopening task details.",
            localizedString("en-US", R.string.automation_export_full_capture_load_failed),
        )
        assertEquals(
            "无法读取此任务的完整诊断设置。请重新打开任务详情后重试。",
            localizedString("zh-CN", R.string.automation_export_full_capture_load_failed),
        )
        assertEquals(
            "無法讀取此任務的完整診斷設定。請重新開啟任務詳情後重試。",
            localizedString("zh-TW", R.string.automation_export_full_capture_load_failed),
        )
        assertEquals(
            "Full capture is disabled. Cleanup of older full-detail data is pending a retry; it cannot be exported.",
            localizedString("en-US", R.string.automation_export_full_capture_cleanup_pending),
        )
        assertEquals(
            "完整采集已关闭，旧完整详情正在等待重试清理，无法导出。",
            localizedString("zh-CN", R.string.automation_export_full_capture_cleanup_pending),
        )
        assertEquals(
            "完整採集已關閉，舊完整詳情正等待重試清理，無法匯出。",
            localizedString("zh-TW", R.string.automation_export_full_capture_cleanup_pending),
        )
    }

    @Suppress("DEPRECATION")
    private fun localizedString(languageTag: String, resourceId: Int): String {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return resources.getString(resourceId)
    }

    @Suppress("DEPRECATION")
    private fun localizedQuantity(languageTag: String, resourceId: Int, quantity: Int): String {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return resources.getQuantityString(resourceId, quantity, quantity)
    }
}
