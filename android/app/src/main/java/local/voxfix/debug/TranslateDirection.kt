package local.voxfix.debug

/** Languages offered by the on-device translator. "Auto" is only valid as a source. */
enum class TranslateLanguage(val preference: String, val label: String, val promptName: String) {
    AUTO("auto", "自动检测", "中文"),
    ZH("zh", "简体中文", "中文"),
    EN("en", "英文", "英文"),
    JA("ja", "日文", "日文"),
    KO("ko", "韩文", "韩文");

    companion object {
        fun fromPreference(value: String?, fallback: TranslateLanguage): TranslateLanguage =
            values().firstOrNull { it.preference == value } ?: fallback
    }
}

/** Auto detection is a source choice and must not overwrite the chosen target. */
data class TranslatePair(val source: TranslateLanguage, val target: TranslateLanguage) {
    fun normalized(): TranslatePair =
        if (target == TranslateLanguage.AUTO) copy(target = TranslateLanguage.ZH)
        else this

    /** Auto has no reverse of its own; from auto→Chinese the useful opposite is Chinese→English. */
    fun swapped(): TranslatePair = when {
        source == TranslateLanguage.AUTO ->
            TranslatePair(target, if (target == TranslateLanguage.ZH) TranslateLanguage.EN else TranslateLanguage.ZH)
        else -> TranslatePair(target, source)
    }

    val label: String get() = source.label + "→" + target.label

    companion object {
        val DEFAULT = TranslatePair(TranslateLanguage.AUTO, TranslateLanguage.ZH)
    }
}
