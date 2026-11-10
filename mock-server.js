// Optional local QA mock. Node.js 18+
// npm init -y && npm install express
// node mock-server.js
const express = require("express");
const app = express();
app.use(express.json());

const LIMIT = 3;
const sessions = new Map();
const requestCache = new Map();
const locks = new Map();

async function withAccountLock(accountId, fn) {
  const previous = locks.get(accountId) || Promise.resolve();
  let release;
  const current = new Promise(resolve => release = resolve);
  const queued = previous.then(() => current);
  locks.set(accountId, queued);
  await previous;
  try { return await fn(); }
  finally {
    release();
    if (locks.get(accountId) === queued) locks.delete(accountId);
  }
}
function accountSessions(id) {
  if (!sessions.has(id)) sessions.set(id, new Map());
  return sessions.get(id);
}
function testOnly(req, res, next) {
  if (req.header("X-Security-Test") !== "true" ||
      req.header("Authorization") !== "Bearer replace-with-staging-test-token") {
    return res.status(403).json({allowed:false, message:"test_environment_only"});
  }
  next();
}
app.post("/v1/test/playback-sessions", testOnly, async (req,res) => {
  const {accountId, deviceId, requestId} = req.body || {};
  if (!accountId || !deviceId || !requestId) return res.status(400).json({allowed:false,message:"missing_fields"});
  const result = await withAccountLock(accountId, async () => {
    if (requestCache.has(requestId)) return requestCache.get(requestId);
    const active = accountSessions(accountId);
    if (active.has(deviceId)) return {allowed:true, activeCount:active.size, message:"already_active"};
    if (active.size >= LIMIT) return {allowed:false, activeCount:active.size, message:"device_limit_reached"};
    active.set(deviceId, {createdAt:Date.now()});
    return {allowed:true, activeCount:active.size, message:"granted"};
  });
  requestCache.set(requestId, result);
  return res.status(result.allowed ? 200 : 409).json(result);
});
app.post("/v1/test/playback-sessions/release", testOnly, async (req,res) => {
  const {accountId, deviceId} = req.body || {};
  if (!accountId || !deviceId) return res.status(400).json({allowed:false,message:"missing_fields"});
  const result = await withAccountLock(accountId, async () => {
    const active = accountSessions(accountId);
    active.delete(deviceId);
    return {allowed:true, activeCount:active.size, message:"released"};
  });
  res.json(result);
});
app.get("/health", (_req,res) => res.json({ok:true, limit:LIMIT}));
app.listen(3000, "0.0.0.0", () => console.log("QA mock listening on :3000"));
