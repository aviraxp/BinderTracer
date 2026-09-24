package com.btrace.viewer.data

/**
 * Android 系统 uid 语义名映射(uid < 10000 即非应用 uid)。
 *
 * 数据源:`platform/system/core/libcutils/include/private/android_filesystem_config.h`
 * 仅收常见的、UI 列表里出现频率高的;未收录的 uid 返回 null。
 *
 * 系统 uid 也可能被多个应用共享(例如 UID 1000)。接收者显示优先使用本表的
 * uid 助记名,避免把系统服务误标成共享该 uid 的某个应用。
 */
object SystemUidNames {
    private val table: Map<Int, String> = mapOf(
        0 to "root",
        1000 to "system",
        1001 to "radio",
        1002 to "bluetooth",
        1003 to "graphics",
        1004 to "input",
        1005 to "audio",
        1006 to "camera",
        1007 to "log",
        1008 to "compass",
        1009 to "mount",
        1010 to "wifi",
        1011 to "adb",
        1012 to "install",
        1013 to "media",
        1014 to "dhcp",
        1015 to "sdcard_rw",
        1016 to "vpn",
        1017 to "keystore",
        1018 to "usb",
        1019 to "drm",
        1020 to "mdnsr",
        1021 to "gps",
        1023 to "media_rw",
        1024 to "mtp",
        1026 to "drmrpc",
        1027 to "nfc",
        1028 to "sdcard_r",
        1029 to "clat",
        1030 to "loop_radio",
        1031 to "mediadrm",
        1032 to "package_info",
        1033 to "sdcard_pics",
        1034 to "sdcard_av",
        1035 to "sdcard_all",
        1036 to "logd",
        1037 to "shared_relro",
        1038 to "dbus",
        1039 to "tlsdate",
        1040 to "media_ex",
        1041 to "audioserver",
        1042 to "metrics_coll",
        1043 to "metricsd",
        1044 to "webserv",
        1045 to "debuggerd",
        1046 to "media_codec",
        1047 to "cameraserver",
        1048 to "firewall",
        1049 to "trunks",
        1050 to "nvram",
        1051 to "dns",
        1052 to "dns_tether",
        1053 to "webview_zygote",
        1054 to "vehicle_network",
        1055 to "media_audio",
        1056 to "media_video",
        1057 to "media_image",
        1058 to "tombstoned",
        1059 to "media_obb",
        1060 to "ese",
        1061 to "ota_update",
        1062 to "automotive_evs",
        1063 to "lowpan",
        1064 to "hsm",
        1065 to "reserved_disk",
        1066 to "statsd",
        1067 to "incidentd",
        1068 to "secure_element",
        1069 to "lmkd",
        1070 to "llkd",
        1071 to "iorapd",
        1072 to "gpu_service",
        1073 to "network_stack",
        1074 to "gsid",
        1075 to "fsverity_cert",
        1076 to "credstore",
        1077 to "external_storage",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        2000 to "shell",
        2001 to "cache",
        2002 to "diag",
        9999 to "nobody",
    )

    /**
     * 返回 uid 对应的系统语义名;非系统 uid (>=10000) 或未知系统 uid 返回 null。
     */
    fun lookup(uid: Int): String? {
        if (uid >= 10000) return null
        return table[uid]
    }
}
