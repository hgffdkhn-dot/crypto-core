package com.enchat.server

import com.enchat.crypto.Bytes
import com.enchat.crypto.E2eeSession
import com.enchat.crypto.GroupCrypto
import com.enchat.crypto.IdentityKeyPair
import com.enchat.crypto.OneTimePreKey
import com.enchat.crypto.PasswordHash
import com.enchat.crypto.SignedPreKey
import com.enchat.crypto.TransportCrypto
import com.enchat.protocol.ErrorCode
import com.enchat.protocol.FrameType
import com.enchat.protocol.MessageKind
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * 端到端集成测试：真实启动 Ktor/Netty 服务端，用真实 WebSocket 走完整流程。
 *
 * 数据库用 H2 内存库（DDL 与 PostgreSQL 版分开维护）；Argon2 参数降到极低以缩短测试时间。
 * 未覆盖的部分见 docs/03-服务端实现.md「测试覆盖与未覆盖」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {

    private lateinit var server: io.ktor.server.engine.ApplicationEngine
    private lateinit var ctx: ServerContext
    private lateinit var serverStatic: TransportCrypto.ServerStaticKeys
    private var port: Int = 0
    private lateinit var baseUrl: String
    private lateinit var tmpDir: File

    @BeforeAll
    fun setup() {
        port = ServerSocket(0).use { it.localPort }
        baseUrl = "http://127.0.0.1:$port"
        tmpDir = File(System.getProperty("java.io.tmpdir"), "enchat-it-" + UUID.randomUUID())
        tmpDir.mkdirs()

        val config = Config(
            port = port,
            host = "127.0.0.1",
            jdbcUrl = "jdbc:h2:mem:itest${System.nanoTime()};DB_CLOSE_DELAY=-1",
            dbUser = "sa",
            dbPassword = "",
            dbPoolSize = 16,
            pepper = Bytes.random(32),
            serverKeyFile = File(tmpDir, "server.key").absolutePath,
            uploadDir = File(tmpDir, "uploads").absolutePath,
            publicBaseUrl = baseUrl,
            e2eeCiphertextRetentionDays = 30,
            isH2 = true
        )
        val ds = Db.create(config)
        val repos = Repos(ds)
        val keys = ServerKeys.loadOrCreate(config.serverKeyFile)
        serverStatic = TransportCrypto.ServerStaticKeys(keys.ed25519Public, keys.x25519Public)
        ctx = ServerContext(
            config = config,
            repos = repos,
            serverKeys = keys,
            limiter = RateLimiter(),
            pepper = config.pepper,
            // 测试专用低参数：真实部署用 PasswordHash.SERVER_PARAMS
            // 测试专用：Argon2 降到极低（生产是 64MiB/t=3），否则 2 核沙盒上单次哈希就要 1s+
            passwordParams = PasswordHash.Params(memoryKiB = 1024, iterations = 1, parallelism = 1),
            // 所有用例都跑在同一 IP 上，放宽除登录外的阈值；登录限速保持生产值以便专门测试
            limits = RateLimitSettings(
                registerPerHourPerIp = 1000,
                loginPerWindowPerIp = 1000,
                loginPerWindowPerUser = 5,
                friendRequestPerDayPerUser = 1000,
                groupCreatePerDayPerUser = 1000,
                sendPerSecondPerUser = 1000,
                keyUploadPerHourPerUser = 1000
            )
        )

        server = embeddedServer(Netty, port = port, host = "127.0.0.1", configure = {
            // 测试里所有请求都打到同一进程，且会留下多条 WebSocket 长连接；
            // 默认线程组（与核数挂钩）在沙盒里会明显拖慢响应，这里显式放大
            connectionGroupSize = 8
            workerGroupSize = 32
            callGroupSize = 64
        }) { module(ctx) }.start(wait = false)
        waitForHealth()
    }

    /** 每个用例结束后强制回收客户端连接，避免长连接堆积影响后续用例。 */
    @org.junit.jupiter.api.AfterEach
    fun closeClients() {
        openedClients.forEach { runCatching { it.close() } }
        openedClients.clear()
    }

    private val openedClients = java.util.Collections.synchronizedList(mutableListOf<TestClient>())

    @AfterAll
    fun teardown() {
        runCatching { server.stop(500, 2000) }
        runCatching { tmpDir.deleteRecursively() }
    }

    /**
     * 预热：受限沙盒上首次 HTTP 请求要十几秒（Netty/Ktor/BouncyCastle 类加载 + JIT），
     * 不预热的话这份冷启动成本会随机落在某个用例上并触发客户端超时。
     */
    private fun warmup() {
        val client = HttpClient.newHttpClient()
        repeat(2) {
            runCatching {
                client.send(
                    HttpRequest.newBuilder(URI.create("$baseUrl/health"))
                        .timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
            }
        }
        runCatching {
            client.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/auth/register"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"warmup\",\"password\":\"MyPassw0rd2024\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
        }
    }

    private fun waitForHealth() {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                val res = client.send(
                    HttpRequest.newBuilder(URI.create("$baseUrl/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                res.statusCode() == 200
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(100)
        }
        error("服务端启动超时")
    }

    // ---------------- 工具 ----------------

    private fun newUser(name: String = "u" + UUID.randomUUID().toString().replace("-", "").take(10)): TestClient {
        val c = TestClient(baseUrl, serverStatic)
        openedClients += c
        val res = c.register(name, "MyPassw0rd2024", name)
        assertTrue(res.optBoolean("ok"), "注册失败: $res")
        return c
    }

    /** 本地生成并上传一整套密钥；返回的 OPK 私钥必须保留，否则无法完成接收侧协商。 */
    private data class KeySet(
        val identity: IdentityKeyPair,
        val spk: SignedPreKey,
        val opks: List<OneTimePreKey>
    )

    private fun setupKeys(c: TestClient): KeySet {
        val identity = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(1, identity)
        val opks = (1..10).map { OneTimePreKey.generate(it) }
        val res = c.uploadKeys(
            identityEd = Bytes.toBase64(identity.ed25519PublicKey),
            identityX = Bytes.toBase64(identity.x25519PublicKey),
            spkId = spk.id,
            spkPub = Bytes.toBase64(spk.publicKey),
            spkSig = Bytes.toBase64(spk.signature),
            opks = opks.map { it.id to Bytes.toBase64(it.publicKey) }
        )
        assertTrue(res.optBoolean("ok"), "上传密钥失败: $res")
        return KeySet(identity, spk, opks)
    }

    private fun makeFriends(a: TestClient, b: TestClient) {
        val res = a.sendSync(FrameType.FRIEND_REQUEST_SEND, org.json.JSONObject()
            .put("toUid", b.uid).put("message", "hi"))
        assertTrue(res.optJSONObject("payload").optBoolean("ok"), "好友请求失败: $res")

        val event = b.waitForType(FrameType.FRIEND_REQUEST_EVENT, 8000)
            ?: error("bob 未收到好友请求事件")
        val requestId = event.getJSONObject("payload").getLong("requestId")

        val accept = b.sendSync(FrameType.FRIEND_ACCEPT, org.json.JSONObject().put("requestId", requestId))
        assertTrue(accept.optJSONObject("payload").optBoolean("ok"), "接受失败: $accept")

        val notified = a.waitForType(FrameType.FRIEND_REQUEST_EVENT, 8000)
            ?: error("alice 未收到接受通知")
        assertEquals(1, notified.getJSONObject("payload").optInt("state"))
    }

    // ---------------- 用例 ----------------

    @Test
    fun `register login and validation`() {
        val name = "u" + UUID.randomUUID().toString().replace("-", "").take(10)
        val c = newUser(name)

        val again = c.register(name, "MyPassw0rd2024")
        assertFalse(again.optBoolean("ok"))
        assertEquals(ErrorCode.USERNAME_TAKEN.code, again.optInt("err"))

        val weak = c.register(name + "x", "12345678")
        assertFalse(weak.optBoolean("ok"))
        assertEquals(ErrorCode.WEAK_PASSWORD.code, weak.optInt("err"))

        val badName = c.register("1bad", "MyPassw0rd2024")
        assertFalse(badName.optBoolean("ok"))

        val login = c.login(name, "MyPassw0rd2024")
        assertTrue(login.optBoolean("ok"))
        assertTrue(login.getJSONObject("data").getString("accessToken").length > 20)

        val wrong = c.login(name, "WrongPass123")
        assertFalse(wrong.optBoolean("ok"))
        assertEquals(ErrorCode.WRONG_CREDENTIALS.code, wrong.optInt("err"))
    }

    @Test
    fun `letter based passwords are accepted and stored safely`() {
        val name = "u" + UUID.randomUUID().toString().replace("-", "").take(10)
        val c = TestClient(baseUrl, serverStatic)
        assertTrue(c.register(name, "Kx9#mQ2vLp7!wR").optBoolean("ok"))
        assertTrue(c.login(name, "Kx9#mQ2vLp7!wR").optBoolean("ok"))

        val row = ctx.repos.findByUsername(name)!!
        val stored = row["password_hash"] as String
        assertTrue(stored.startsWith("argon2id\$v=19\$"))
        assertFalse(stored.contains("Kx9#mQ2vLp7!wR"))
    }

    @Test
    fun `login is rate limited`() {
        val name = "u" + UUID.randomUUID().toString().replace("-", "").take(10)
        val c = newUser(name)
        var limited = false
        repeat(10) {
            val res = c.login(name, "definitely-wrong")
            if (res.optInt("err") == ErrorCode.RATE_LIMITED.code) limited = true
        }
        assertTrue(limited, "连续失败登录应触发限速")
    }

    @Test
    fun `profile and avatar upload`() {
        val c = newUser()
        assertTrue(c.setProfile("新昵称").optBoolean("ok"))

        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(120) { it.toByte() }
        val up = c.uploadAvatar("image/png", Bytes.toBase64(png))
        assertTrue(up.optBoolean("ok"), "头像上传失败: $up")
        val url = up.getJSONObject("data").getString("avatarUrl")
        assertTrue(url.startsWith("$baseUrl/files/"))

        val fetched = c.fetchAvatar(url.removePrefix(baseUrl))
        assertEquals(200, fetched.statusCode())

        val badMime = c.uploadAvatar("image/gif", Bytes.toBase64(png))
        assertFalse(badMime.optBoolean("ok"))
        val tooBig = c.uploadAvatar("image/png", Bytes.toBase64(ByteArray(3 * 1024 * 1024)))
        assertFalse(tooBig.optBoolean("ok"))

        val traversal = c.fetchAvatar("/files/../../etc/passwd")
        assertTrue(traversal.statusCode() == 404 || traversal.statusCode() == 400)
    }

    @Test
    fun `prekey bundle fetch consumes one time prekey`() {
        val a = newUser()
        val b = newUser()
        setupKeys(a)
        setupKeys(b)

        val first = a.bundle(b.uid).getJSONObject("data")
        val opk1 = first.getInt("oneTimePreKeyId")
        assertEquals(32, Bytes.fromBase64(first.getString("identityEd25519Public")).size)
        assertEquals(32, Bytes.fromBase64(first.getString("signedPreKeyPublic")).size)
        assertEquals(64, Bytes.fromBase64(first.getString("signedPreKeySignature")).size)

        val second = a.bundle(b.uid).getJSONObject("data")
        assertNotEquals(opk1, second.optInt("oneTimePreKeyId"), "OPK 应被用完即焚")
    }

    @Test
    fun `ws handshake auth and ping pong`() {
        val a = newUser()
        val result = a.connect()
        assertTrue(result.optBoolean("ok"), "鉴权失败: $result")
        assertTrue(result.getJSONObject("payload").optBoolean("ok"))
        assertTrue(a.pingPong())
        a.close()
    }

    @Test
    fun `frames before auth are rejected`() {
        val a = newUser()
        val b = newUser()
        a.connect(); b.connect()
        makeFriends(a, b)
        val conv = a.direct(b.uid, 1).getJSONObject("data").getLong("conversationId")

        val fresh = TestClient(baseUrl, serverStatic)
        // 只做传输层握手、不鉴权，直接发消息
        fresh.token = a.token
        fresh.connect(auth = false)
        val res = fresh.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv).put("kind", 1).put("text", "x"))
        assertFalse(res.optBoolean("ok"))
        a.close(); b.close(); fresh.close()
    }

    @Test
    fun `standard mode chat delivers plaintext and server stores plaintext`() {
        val a = newUser()
        val b = newUser()
        a.connect(); b.connect()
        makeFriends(a, b)
        val conv = a.direct(b.uid, 1).getJSONObject("data").getLong("conversationId")

        val ack = a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv)
            .put("kind", MessageKind.TEXT.code)
            .put("clientMsgId", "m1")
            .put("text", "你好，这是标准加密消息"))
        assertTrue(ack.optBoolean("ok"), "发送失败: $ack")

        val delivered = b.waitForType(FrameType.MESSAGE_DELIVER, 8000)
            ?: error("bob 未收到消息")
        val payload = delivered.getJSONObject("payload")
        assertEquals("你好，这是标准加密消息", payload.optString("text"))
        assertEquals(conv, payload.getLong("conversationId"))

        // 服务端确实以明文落库（这是需求要求的默认行为）
        val rows = ctx.repos.listMessagesAfter(b.userId, 0, 100).filter { it.conversationId == conv }
        assertTrue(rows.any { it.plaintext == "你好，这是标准加密消息" && it.ciphertext == null })
        a.close(); b.close()
    }

    @Test
    fun `e2ee chat keeps server blind to content`() {
        val a = newUser()
        val b = newUser()
        val (aId, _, _) = setupKeys(a)
        val (bId, bSpk, bOpks) = setupKeys(b)

        a.connect(); b.connect()
        makeFriends(a, b)
        val conv = a.direct(b.uid, 2).getJSONObject("data").getLong("conversationId")

        // alice 取 bob 的公钥包 → 建立会话 → 发送 E2EE_INIT
        val bundleData = a.bundle(b.uid).getJSONObject("data")
        val bundle = bundleToPreKeyBundle(bundleData)
        val (aliceSession, initMessage) = E2eeSession.createAsSender(aId, a.userId, bundle)

        val initAck = a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv)
            .put("kind", MessageKind.E2EE_INIT.code)
            .put("clientMsgId", "init")
            .put("e2ee", org.json.JSONObject()
                .put("sessionId", Bytes.toBase64(aliceSession.sessionId))
                .put("ciphertext", Bytes.toBase64(initMessage.toBytes()))))
        assertTrue(initAck.optBoolean("ok"), "E2EE_INIT 发送失败: $initAck")

        val initDelivered = b.waitForType(FrameType.MESSAGE_DELIVER, 8000)
            ?: error("bob 未收到 E2EE_INIT")
        val initCt = initDelivered.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")
        val receivedInit = com.enchat.crypto.X3dh.InitMessage.fromBytes(Bytes.fromBase64(initCt))
        val opkId = receivedInit.receiverOneTimePreKeyId
        val bobSession = E2eeSession.createAsReceiver(
            bId, bSpk, opkId?.let { id -> bOpks.first { it.id == id } }, receivedInit
        )
        assertArrayEquals(aliceSession.sessionId, bobSession.sessionId)
        assertEquals(aliceSession.safetyNumber(), bobSession.safetyNumber())

        // 双向端到端消息
        val secret = "这是一条端到端加密的消息 🔒"
        val ct = aliceSession.encrypt(secret.toByteArray())
        val ack = a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv)
            .put("kind", MessageKind.TEXT.code)
            .put("clientMsgId", "e1")
            .put("e2ee", org.json.JSONObject()
                .put("sessionId", Bytes.toBase64(aliceSession.sessionId))
                .put("ciphertext", Bytes.toBase64(ct))))
        assertTrue(ack.optBoolean("ok"))

        val delivered = b.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("bob 未收到密文")
        val e2eePayload = delivered.getJSONObject("payload").getJSONObject("e2ee")
        val plaintext = bobSession.decrypt(Bytes.fromBase64(e2eePayload.getString("ciphertext")))
        assertEquals(secret, String(plaintext))

        // 服务端只见到密文
        val rows = ctx.repos.listMessagesAfter(b.userId, 0, 200).filter { it.conversationId == conv }
        assertTrue(rows.any { it.ciphertext != null && it.plaintext == null })
        assertTrue(rows.none { it.plaintext?.contains(secret) == true })

        // bob 回复
        val replyCt = bobSession.encrypt("收到".toByteArray())
        b.send(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv)
            .put("kind", MessageKind.TEXT.code)
            .put("clientMsgId", "e2")
            .put("e2ee", org.json.JSONObject()
                .put("sessionId", Bytes.toBase64(bobSession.sessionId))
                .put("ciphertext", Bytes.toBase64(replyCt))))
        val back = a.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("alice 未收到回复")
        assertEquals("收到", String(aliceSession.decrypt(
            Bytes.fromBase64(back.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")))))

        a.close(); b.close()
    }

    @Test
    fun `e2ee conversation rejects plaintext downgrade`() {
        val a = newUser()
        val b = newUser()
        setupKeys(a); setupKeys(b)
        a.connect(); b.connect()
        makeFriends(a, b)
        val conv = a.direct(b.uid, 2).getJSONObject("data").getLong("conversationId")

        val res = a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv)
            .put("kind", MessageKind.TEXT.code)
            .put("text", "明文降级尝试"))
        assertFalse(res.optBoolean("ok"))
        assertEquals(ErrorCode.E2EE_REQUIRED.code, res.optInt("err"))
        a.close(); b.close()
    }

    @Test
    fun `e2ee handles out of order delivery on wire`() {
        // 复用会话建立逻辑，验证乱序投递后仍能解密（棘轮层已在单元测试覆盖，这里验证"经服务器中转"的链路）
        val a = newUser()
        val b = newUser()
        val (aId, _, _) = setupKeys(a)
        val (bId, bSpk, bOpks) = setupKeys(b)
        a.connect(); b.connect()
        makeFriends(a, b)
        val conv = a.direct(b.uid, 2).getJSONObject("data").getLong("conversationId")

        val bundle = bundleToPreKeyBundle(a.bundle(b.uid).getJSONObject("data"))
        val (alice, init) = E2eeSession.createAsSender(aId, a.userId, bundle)
        a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv).put("kind", MessageKind.E2EE_INIT.code)
            .put("e2ee", org.json.JSONObject().put("ciphertext", Bytes.toBase64(init.toBytes()))))

        val delivered = b.waitForType(FrameType.MESSAGE_DELIVER, 8000)!!
        val got = com.enchat.crypto.X3dh.InitMessage.fromBytes(
            Bytes.fromBase64(delivered.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")))
        val bob = E2eeSession.createAsReceiver(bId, bSpk, got.receiverOneTimePreKeyId?.let { id -> bOpks.first { it.id == id } }, got)

        val messages = listOf("第一条", "第二条", "第三条")
        val frames = mutableListOf<ByteArray>()
        messages.forEachIndexed { i, text ->
            val ct = alice.encrypt(text.toByteArray())
            frames += ct
            a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
                .put("conversationId", conv).put("kind", MessageKind.TEXT.code)
                .put("clientMsgId", "o$i")
                .put("e2ee", org.json.JSONObject().put("ciphertext", Bytes.toBase64(ct))))
        }
        // bob 侧按 2,0,1 的顺序解密（模拟乱序到达）
        val received = (0..2).map { i ->
            val d = b.waitForType(FrameType.MESSAGE_DELIVER, 8000)
                ?: error("bob 未收到第 $i 条")
            d.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")
        }
        assertEquals("第二条", String(bob.decrypt(Bytes.fromBase64(received[1]))))
        assertEquals("第一条", String(bob.decrypt(Bytes.fromBase64(received[0]))))
        assertEquals("第三条", String(bob.decrypt(Bytes.fromBase64(received[2]))))
        a.close(); b.close()
    }

    @Test
    fun `group chat works with sender key and epoch rotation`() {
        val a = newUser()
        val b = newUser()
        val c = newUser()
        val (aId, _, _) = setupKeys(a)
        val (bId, bSpk, bOpks) = setupKeys(b)
        val (cId, cSpk, cOpks) = setupKeys(c)

        a.connect(); b.connect(); c.connect()
        makeFriends(a, b); makeFriends(a, c)

        // alice 与 bob、carol 各建一条双边 E2EE 会话（用于分发 sender key）
        /** 建立双边 E2EE 会话；返回（alice 侧会话、对方侧会话、单聊会话 id）。 */
        fun pairSession(from: TestClient, fromId: IdentityKeyPair, to: TestClient, spk: SignedPreKey, opks: List<OneTimePreKey>): Triple<E2eeSession, E2eeSession, Long> {
            val bundle = bundleToPreKeyBundle(from.bundle(to.uid).getJSONObject("data"))
            val (senderSession, init) = E2eeSession.createAsSender(fromId, from.userId, bundle)
            val conv = from.direct(to.uid, 2).getJSONObject("data").getLong("conversationId")
            from.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
                .put("conversationId", conv).put("kind", MessageKind.E2EE_INIT.code)
                .put("e2ee", org.json.JSONObject().put("ciphertext", Bytes.toBase64(init.toBytes()))))
            val d = to.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("未收到 init")
            val got = com.enchat.crypto.X3dh.InitMessage.fromBytes(
                Bytes.fromBase64(d.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")))
            val receiverSession = E2eeSession.createAsReceiver(
                if (to === b) bId else cId, spk, got.receiverOneTimePreKeyId?.let { id -> opks.first { it.id == id } }, got)
            return Triple(senderSession, receiverSession, conv)
        }

        val (aliceToBob, bobFromAlice, bobDirectConv) = pairSession(a, aId, b, bSpk, bOpks)
        val (aliceToCarol, carolFromAlice, carolDirectConv) = pairSession(a, aId, c, cSpk, cOpks)

        // 建群（端到端模式）
        val createRes = a.sendSync(FrameType.GROUP_CREATE, org.json.JSONObject()
            .put("title", "周末爬山")
            .put("encryptionMode", 2)
            .put("memberUids", org.json.JSONArray().put(b.uid).put(c.uid)))
        val groupPayload = createRes.getJSONObject("payload")
        val conversationId = groupPayload.getLong("conversationId")
        val groupId = Bytes.fromBase64(groupPayload.getString("groupId"))
        assertEquals(16, groupId.size)
        assertEquals(1, groupPayload.optInt("epoch"))

        // 分发 sender key（epoch 1）。
        // 必须走**各自的 1:1 端到端会话**，不能发到群里：群广播会把只该给某人的密钥推给所有成员，
        // 其他人虽解不开（会话不同），但会造成消息错配、也违反最小暴露原则。
        val senderKey1 = GroupCrypto.SenderKeyState.create(groupId, 1)
        listOf(aliceToBob to bobDirectConv, aliceToCarol to carolDirectConv).forEach { (session, conv) ->
            val payload = session.encrypt(senderKey1.distributionPayload())
            a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
                .put("conversationId", conv)
                .put("kind", MessageKind.E2EE_SENDER_KEY.code)
                .put("e2ee", org.json.JSONObject().put("ciphertext", Bytes.toBase64(payload))))
        }
        val bobKeyEvent = b.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("bob 未收到 sender key")
        val carolKeyEvent = c.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("carol 未收到 sender key")
        val bobReceiver1 = GroupCrypto.ReceiverKeyState.fromDistributionPayload(
            bobFromAlice.decrypt(Bytes.fromBase64(bobKeyEvent.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext"))), a.userId)
        val carolReceiver1 = GroupCrypto.ReceiverKeyState.fromDistributionPayload(
            carolFromAlice.decrypt(Bytes.fromBase64(carolKeyEvent.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext"))), a.userId)

        // 群消息（加密一次，广播给所有人）
        val groupText = "周六早上 7 点集合"
        val wire = senderKey1.encrypt(groupText.toByteArray(), a.userId)
        a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conversationId)
            .put("kind", MessageKind.TEXT.code)
            .put("e2ee", org.json.JSONObject()
                .put("ciphertext", Bytes.toBase64(wire))
                .put("epoch", 1)))
        val bobMsg = b.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("bob 未收到群消息")
        val carolMsg = c.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("carol 未收到群消息")
        assertEquals(groupText, String(bobReceiver1.decrypt(Bytes.fromBase64(bobMsg.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")))))
        assertEquals(groupText, String(carolReceiver1.decrypt(Bytes.fromBase64(carolMsg.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext")))))

        // 邀请新成员 → epoch 提升到 2
        val d = newUser()
        setupKeys(d)
        d.connect()
        makeFriends(a, d)
        val invite = a.sendSync(FrameType.GROUP_INVITE, org.json.JSONObject()
            .put("conversationId", conversationId)
            .put("uids", org.json.JSONArray().put(d.uid)))
        val newEpoch = invite.getJSONObject("payload").optInt("epoch")
        assertEquals(2, newEpoch, "成员变动必须提升 epoch")

        // 成员变动后必须**先分发新链、再用新链发消息**：
        // distributionPayload 里带的是"当前迭代位置"，若先用链加密消息再分发，
        // 接收方起点就会落后于已发出的消息，导致这批消息永远解不开（顺序要求）。
        val senderKey2 = GroupCrypto.SenderKeyState.create(groupId, newEpoch)
        val payload2 = aliceToBob.encrypt(senderKey2.distributionPayload())
        a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", bobDirectConv)
            .put("kind", MessageKind.E2EE_SENDER_KEY.code)
            .put("e2ee", org.json.JSONObject().put("ciphertext", Bytes.toBase64(payload2))))
        val bobKeyEvent2 = b.waitForType(FrameType.MESSAGE_DELIVER, 8000) ?: error("bob 未收到轮换后的 sender key")
        val bobReceiver2 = GroupCrypto.ReceiverKeyState.fromDistributionPayload(
            bobFromAlice.decrypt(Bytes.fromBase64(bobKeyEvent2.getJSONObject("payload").getJSONObject("e2ee").getString("ciphertext"))), a.userId)

        val wire2 = senderKey2.encrypt("新人已加入".toByteArray(), a.userId)

        // 旧 epoch 的接收状态解不开新 epoch 的消息（已离群/旧链成员被挡住）
        assertThrows(com.enchat.crypto.CryptoException::class.java) { bobReceiver1.decrypt(wire2) }
        assertEquals("新人已加入", String(bobReceiver2.decrypt(wire2)))

        listOf(a, b, c, d).forEach { it.close() }
    }

    @Test
    fun `offline messages are delivered via sync`() {
        val a = newUser()
        val b = newUser()
        a.connect(); b.connect()
        makeFriends(a, b)
        val conv = a.direct(b.uid, 1).getJSONObject("data").getLong("conversationId")

        b.close() // bob 离线
        Thread.sleep(200)

        a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv).put("kind", MessageKind.TEXT.code).put("text", "离线消息 1"))
        a.sendSync(FrameType.SEND_MESSAGE, org.json.JSONObject()
            .put("conversationId", conv).put("kind", MessageKind.TEXT.code).put("text", "离线消息 2"))

        val b2 = TestClient(baseUrl, serverStatic)
        val login = b2.login(
            ctx.repos.toUser(ctx.repos.userIdRow(b.userId)!!).username, "MyPassw0rd2024")
        assertTrue(login.optBoolean("ok"))
        b2.connect()
        val sync = b2.sendSync(FrameType.SYNC_REQUEST, org.json.JSONObject().put("afterSeq", 0).put("limit", 100))
        val arr = sync.getJSONObject("payload").getJSONArray("messages")
        val texts = (0 until arr.length()).map { arr.getJSONObject(it).optString("text") }
        assertTrue(texts.contains("离线消息 1") && texts.contains("离线消息 2"), "同步未包含离线消息: $texts")
        a.close(); b2.close()
    }

    @Test
    fun `friend list and removal`() {
        val a = newUser()
        val b = newUser()
        a.connect(); b.connect()
        makeFriends(a, b)

        val list = a.sendSync(FrameType.FRIEND_LIST, org.json.JSONObject())
        val friends = list.getJSONObject("payload").getJSONArray("friends")
        val uids = (0 until friends.length()).map { friends.getJSONObject(it).getLong("uid") }
        assertTrue(uids.contains(b.uid), "好友列表缺少 bob: $uids")

        a.sendSync(FrameType.FRIEND_REMOVE, org.json.JSONObject().put("friendUid", b.uid))
        val after = a.sendSync(FrameType.FRIEND_LIST, org.json.JSONObject())
        val uids2 = (0 until after.getJSONObject("payload").getJSONArray("friends").length())
            .map { after.getJSONObject("payload").getJSONArray("friends").getJSONObject(it).getLong("uid") }
        assertFalse(uids2.contains(b.uid))
        a.close(); b.close()
    }

    @Test
    fun `wrong server static key makes handshake fail`() {
        // 客户端持有一份错误的服务器公钥 → 握手应被拒绝（模拟未 pinning 时被 MITM）
        val a = newUser()
        val rogue = IdentityKeyPair.generate()
        val bad = TestClient(baseUrl, TransportCrypto.ServerStaticKeys(rogue.ed25519PublicKey, rogue.x25519PublicKey))
        bad.token = a.token
        assertThrows(IllegalStateException::class.java) { bad.connect() }
    }
}
