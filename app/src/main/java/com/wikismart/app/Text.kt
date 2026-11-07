package com.wikismart.app

object Text {
    private val tashkeel = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val notWord = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    /** Lower-case, strip Arabic diacritics and unify common Arabic letter variants. */
    fun norm(s: String): String = s.lowercase()
        .replace(tashkeel, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(notWord, " ")
        .replace(spaces, " ")
        .trim()

    fun hasArabic(s: String): Boolean = s.any { it in '؀'..'ۿ' }
}

/** Short spoken / logged messages in Arabic (Egyptian) and English. */
object Msgs {
    fun noA11y(ar: Boolean) =
        if (ar) "فعّل خدمة Wikismart من إعدادات إمكانية الوصول الأول"
        else "Please enable the Wikismart accessibility service first."

    fun noApp(ar: Boolean) =
        if (ar) "مش قادر أفتح تطبيق الفيشة، اختاره من الإعدادات"
        else "I can't open the plug app. Please pick it in settings."

    fun notFound(ar: Boolean) =
        if (ar) "ما لقيتش زر التحكم في التطبيق"
        else "I couldn't find the control in the app."

    fun done(ar: Boolean, on: Boolean) =
        if (ar) (if (on) "تم التشغيل" else "تم الإطفاء")
        else (if (on) "Done, it's on." else "Done, it's off.")

    fun already(ar: Boolean, on: Boolean) =
        if (ar) (if (on) "الفيشة شغالة بالفعل" else "الفيشة مطفية بالفعل")
        else (if (on) "It's already on." else "It's already off.")

    fun tappedPower(ar: Boolean) =
        if (ar) "ضغطت على زر الطاقة"
        else "I tapped the power button."

    fun many(ar: Boolean, on: Boolean, ok: Int, total: Int) =
        if (ar) "تم ${if (on) "تشغيل" else "إطفاء"} $ok من $total"
        else "Turned ${if (on) "on" else "off"} $ok of $total plugs."

    fun didntHear(ar: Boolean) =
        if (ar) "ما سمعتش أمر" else "I didn't hear a command."
}
