# Android 并发限制安全测试器（MVP）

本项目是面向自有 QA / staging 环境的 Android 原生测试客户端。它模拟多个独立设备身份，并发调用专用测试授权接口，统计成功授权数量，并对成功会话发送释放请求。

它不会调用腾讯视频正式播放 API，不获取真实播放 URL，不修改正式账号权益，也不包含绕过正式服务限制的逻辑。

## 环境
- Android Studio
- JDK 17
- Android SDK 35
- Android 8.0+（minSdk 26）

导入本目录，等待 Gradle Sync 后运行 app。

## 接口约定

### 并发授权
POST /v1/test/playback-sessions

Headers: Authorization: Bearer <staging-test-token>、X-Security-Test: true、X-Request-ID: <unique-id>、Content-Type: application/json

Body 示例：
{"accountId":"qa-account-001","deviceId":"qa-device-1","requestId":"uuid","purpose":"concurrency-security-test"}

允许响应：{"allowed":true,"activeCount":1,"message":"granted"}

拒绝响应（建议 HTTP 409）：{"allowed":false,"activeCount":3,"message":"device_limit_reached"}

### 释放会话
POST /v1/test/playback-sessions/release

Body：{"accountId":"qa-account-001","deviceId":"qa-device-1"}

Response：{"allowed":true,"activeCount":2,"message":"released"}

## 服务端必须保证的安全属性
1. **原子性**：同一账号的检查活跃设备数和创建会话必须处于同一事务或原子脚本内。
2. **幂等性**：requestId 应有唯一约束；同一请求重试不能创建多个会话。
3. **设备身份**：不要仅相信客户端传入的 deviceId，应使用服务端签发的设备凭据或可信设备注册记录。
4. **会话生命周期**：释放、超时、心跳、崩溃回收都要有明确状态机。
5. **限流与审计**：测试账号、测试 Token、测试环境应隔离；记录 accountId、deviceId、requestId、决策、activeCount、事务结果和时间。
6. **一致性**：多实例部署时不能只用单进程内存锁，应使用数据库事务、行锁、唯一约束或 Redis Lua 等共享原子机制。

## 测试解释
- 模拟设备数默认 10，预期上限默认 3。
- 如果成功授权数大于 3，标记为疑似超额授权；需结合服务端数据库/缓存和请求日志复核。
- 如果小于 3，不一定是限制正确，也可能是账号状态、接口错误、测试数据或环境配置导致。
- App 中的模拟设备是逻辑身份，不等同于真实手机。最终需在真实多设备、多个服务端实例和生产同构环境复测。
- 建议先用 4 台并发，再测 10、20、50；每轮后释放会话并确认服务端活跃会话归零。

## 网络提示
模拟器访问宿主机本地服务通常使用 http://10.0.2.2:3000。真机应填写测试服务器在局域网/VPN 中可达的地址。生产环境请使用 HTTPS；本 MVP 为便于本地调试开启 cleartext traffic，不建议将该设置带入正式发布包。
