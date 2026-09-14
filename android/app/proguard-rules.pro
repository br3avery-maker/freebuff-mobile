# Freebuff R8 规则
#
# 工程 JSON 解析使用 Android 内置 org.json(无反射依赖),未使用
# Retrofit / Gson / kotlinx-serialization 反射序列化,因此无需额外 keep 规则,
# 默认的 proguard-android-optimize.txt 已覆盖 Compose / Hilt 等自带 consumer rules 的库。
#
# 若未来引入反射型序列化(如 kotlinx.serialization 的 @Serializable 或 Gson),
# 在此补充对应 keep 规则后再发布。
