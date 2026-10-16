package com.enchat.app.data.remote

import org.json.JSONObject

/**
 * 构造 [JSONObject] 的安全入口。
 *
 * 为什么不能直接用 `JSONObject().put(k, v)`：
 * org.json 的 `JSONObject` 同时存在 `put(String, Object)` 与 `put(String, int/long/boolean/double)`
 * 两套重载。Kotlin 在参数是泛型推断结果、或接收者为平台类型（`JSONObject!`）时，
 * 无法确定该走哪一套，于是报：
 *
 * ```
 * Overload resolution ambiguity:
 *   public open fun put(p0: String!, p1: Any!): JSONObject!
 *   public open fun put(p0: String!, p1: Long): JSONObject!
 *   ...
 * ```
 *
 * 这里把值统一声明为 [Any]，只会命中 `put(String, Object)`，从类型层面消除歧义。
 * 代价是所有数值都会被装箱（JSON 语义不变）。
 */
fun jsonObjectOf(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
    pairs.forEach { (key, value) -> put(key, value) }
}
