package com.enchat.crypto

/**
 * 口令策略：允许**字母 + 数字 + 常用符号**（不再限定纯数字）。
 *
 * 为什么要放宽：纯数字 8 位只有 10^8 组合，GPU 爆破成本极低，靠 KDF 只能抬高成本、无法根治。
 * 允许字母后，同样长度的口令空间提升到 62^8 ≈ 2×10^14（约 2^47），配合 Argon2id 才有实际意义。
 *
 * 设计原则：
 * - 只做**结构性**校验（长度、字符集、常见弱口令、键盘序列、包含用户名），不做"必须含大小写+符号"这类
 *   已被 NIST SP 800-63B 指出会促使用户写可预测口令的强制组合规则。
 * - 返回"问题列表"而非布尔值，便于 UI 逐条提示；同时给出 0..4 的强度分，供前端显示强度条。
 *
 * 局限（诚实说明）：内置黑名单只有几十条，无法覆盖真实口令字典。生产环境建议接入
 * Have I Been Pwned 的 k-anonymity 口令范围查询（只传哈希前 5 位，不泄露完整口令哈希）。
 */
object PasswordPolicy {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64

    data class Result(
        /** 阻塞性问题：非空即拒绝注册/修改。 */
        val problems: List<String>,
        /** 0=不合格 1=弱 2=中 3=强 4=很强；仅当 problems 为空时有意义。 */
        val score: Int
    ) {
        val accepted: Boolean get() = problems.isEmpty()
    }

    /** 允许的可打印 ASCII 符号（不含空格与控制字符）。 */
    private val ALLOWED_SYMBOLS = setOf(
        '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        ':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~'
    )

    /**
     * 常见弱口令黑名单（小写比对）。
     * 只保留高频样本；真正的字典检查应走外部服务。
     */
    private val WEAK_LIST = setOf(
        "password", "password1", "password123", "passw0rd", "p@ssword", "p@ssw0rd",
        "12345678", "123456789", "1234567890", "1234567", "87654321", "11111111", "00000000",
        "qwerty", "qwertyuiop", "qwerty123", "qwertyui", "asdfghjkl", "asdfgh",
        "iloveyou", "letmein", "welcome", "admin", "admin123", "root", "toor",
        "abc123", "abc12345", "abcd1234", "a123456", "1q2w3e4r", "1qaz2wsx",
        "monkey", "dragon", "sunshine", "princess", "football", "baseball", "master",
        "enchat", "chat123", "test1234", "test123", "changeme", "secret", "starwars",
        "trustno1", "whatever", "hello123", "freedom", "ninja", "mustang", "michael"
    )

    /** 键盘行序列（用于识别 qwerty / asdf / 123 这类走位口令）。 */
    private val KEYBOARD_ROWS = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    fun validate(password: String, username: String? = null): Result {
        val problems = mutableListOf<String>()

        if (password.isEmpty()) {
            problems += "密码不能为空"
            return Result(problems, 0)
        }
        if (password.length < MIN_LENGTH) problems += "密码至少 $MIN_LENGTH 位"
        if (password.length > MAX_LENGTH) problems += "密码最长 $MAX_LENGTH 位"
        if (password.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in ALLOWED_SYMBOLS }) {
            problems += "密码含不支持的字符（支持字母、数字与常见符号，不支持空格和中文）"
        }
        if (password.toSet().size <= 2) problems += "密码过于简单（字符种类过少）"
        if (isSingleCharRepeat(password)) problems += "密码不能是同一字符重复"
        if (hasLongRun(password, 4)) problems += "密码不能含 4 位以上连续递增/递减序列（如 abcd、4321）"
        if (matchesKeyboardRun(password, 4)) problems += "密码不能含键盘连排序列（如 qwer、asdf）"
        if (WEAK_LIST.contains(password.lowercase())) problems += "密码是常见弱口令，请更换"
        if (containsSequentialDigitsFilling(password)) problems += "密码不能整体为连续数字"

        username?.let { u ->
            if (u.length >= 3 && password.lowercase().contains(u.lowercase())) {
                problems += "密码不能包含用户名"
            }
        }

        val score = if (problems.isNotEmpty()) 0 else score(password)
        return Result(problems, score)
    }

    /** 强度分：综合长度、字符种类与是否命中弱结构。仅用于 UI 展示，不作为准入条件。 */
    private fun score(password: String): Int {
        val kinds = charKinds(password)
        var s = 0
        s += when {
            password.length >= 20 -> 2
            password.length >= 14 -> 1
            else -> 0
        }
        s += when {
            kinds >= 4 -> 2
            kinds == 3 -> 1
            else -> 0
        }
        // 全是小写字母或全是数字的（且已通过其他校验）最多算"中"
        if (kinds <= 1) s = minOf(s, 1)
        return s.coerceIn(0, 4)
    }

    fun charKinds(password: String): Int {
        var kinds = 0
        if (password.any { it in 'a'..'z' }) kinds++
        if (password.any { it in 'A'..'Z' }) kinds++
        if (password.any { it in '0'..'9' }) kinds++
        if (password.any { it in ALLOWED_SYMBOLS }) kinds++
        return kinds
    }

    private fun isSingleCharRepeat(s: String): Boolean = s.isNotEmpty() && s.all { it == s[0] }

    /** 是否存在长度 >= n 的码点连续递增/递减片段。 */
    private fun hasLongRun(s: String, n: Int): Boolean {
        if (s.length < n) return false
        var inc = 1
        var dec = 1
        for (i in 1 until s.length) {
            val d = s[i].code - s[i - 1].code
            inc = if (d == 1) inc + 1 else 1
            dec = if (d == -1) dec + 1 else 1
            if (inc >= n || dec >= n) return true
        }
        return false
    }

    /** 是否含键盘行上的连排片段（长度 >= n，正序或反序）。 */
    private fun matchesKeyboardRun(s: String, n: Int): Boolean {
        val lower = s.lowercase()
        for (row in KEYBOARD_ROWS) {
            if (containsRun(lower, row, n)) return true
            if (containsRun(lower, row.reversed(), n)) return true
        }
        return false
    }

    private fun containsRun(text: String, row: String, n: Int): Boolean {
        for (i in 0..row.length - n) {
            val seg = row.substring(i, i + n)
            if (text.contains(seg)) return true
        }
        return false
    }

    private fun containsSequentialDigitsFilling(s: String): Boolean {
        if (s.length < 4 || !s.all { it in '0'..'9' }) return false
        return hasLongRun(s, s.length)
    }
}
