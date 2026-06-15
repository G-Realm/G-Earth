import "frida-il2cpp-bridge";

function log(message: string): void { try { console.log(message); } catch (e) {} }

const debugHeaders = false;

// bridge protocol, mirrors the java side
const FRAME_NOTIFY = 0;
const FRAME_INTERCEPT = 1;
const DIR_TO_CLIENT = 0;
const DIR_TO_SERVER = 1;
const TAG_VERDICT = 0x10;
const TAG_INJECT = 0x20;
const MAX_HEADER = 4000;

// port and cookie come from frida-inject -P through rpc.exports.init
let bridgeport = 9399;
let cookie = "";
rpc.exports = {
  init(stage: string, params: any): void {
    if (params) {
      if (params.port) bridgeport = params.port;
      if (params.cookie) cookie = params.cookie;
    }
  },
};
const CLIENT_VERSION = "UNITY20";

// raw socket, fridas Socket api is async but the hooks run sync
const winsock = Module.load("ws2_32.dll");
const wsaStartup = new NativeFunction(winsock.getExportByName("WSAStartup"), "int", ["uint32", "pointer"]);
const openSocket = new NativeFunction(winsock.getExportByName("socket"), "uint32", ["int", "int", "int"]);
const connectSocket = new NativeFunction(winsock.getExportByName("connect"), "int", ["uint32", "pointer", "int"]);
const socketSend = new NativeFunction(winsock.getExportByName("send"), "int", ["uint32", "pointer", "int", "int"], { scheduling: "exclusive" });
const socketRecv = new NativeFunction(winsock.getExportByName("recv"), "int", ["uint32", "pointer", "int", "int"], { scheduling: "exclusive" });
const closeSocket = new NativeFunction(winsock.getExportByName("closesocket"), "int", ["uint32"]);
const htons = new NativeFunction(winsock.getExportByName("htons"), "uint16", ["uint16"]);
const setSockOpt = new NativeFunction(winsock.getExportByName("setsockopt"), "int", ["uint32", "int", "int", "pointer", "int"]);
const ioctlSocket = new NativeFunction(winsock.getExportByName("ioctlsocket"), "int", ["uint32", "int", "pointer"]);
const FIONREAD = 0x4004667f;
const INVALID_SOCKET = 0xffffffff;

const kernel32 = Module.load("kernel32.dll");
const initCriticalSection = new NativeFunction(kernel32.getExportByName("InitializeCriticalSection"), "void", ["pointer"]);
const enterCriticalSection = new NativeFunction(kernel32.getExportByName("EnterCriticalSection"), "void", ["pointer"]);
const leaveCriticalSection = new NativeFunction(kernel32.getExportByName("LeaveCriticalSection"), "void", ["pointer"]);
const getCurrentProcess = new NativeFunction(kernel32.getExportByName("GetCurrentProcess"), "pointer", []);
const terminateProcess = new NativeFunction(kernel32.getExportByName("TerminateProcess"), "int", ["pointer", "uint"]);
const criticalSection = Memory.alloc(64); initCriticalSection(criticalSection);

let bridgeSocket = INVALID_SOCKET, bridgeReady = false;

function connectBridge(revision: string, host: string): boolean {
  const wsaData = Memory.alloc(512);
  wsaStartup(0x0202, wsaData);
  const sock = (openSocket(2, 1, 6) as number);
  if (sock === INVALID_SOCKET) return false;
  const addr = Memory.alloc(16);
  addr.writeU16(2);
  addr.add(2).writeU16(htons(bridgeport) as number);
  addr.add(4).writeU32(0x0100007f);
  if ((connectSocket(sock, addr, 16) as number) !== 0) { closeSocket(sock); return false; }
  bridgeSocket = sock;
  const recvTimeout = Memory.alloc(4); recvTimeout.writeU32(2000);
  setSockOpt(sock, 0xffff, 0x1006, recvTimeout, 4);
  const marker = Memory.alloc(1); marker.writeU8(0xff);
  if (!sendAll(marker, 1) || !sendstr(cookie) || !sendstr(revision) || !sendstr(host)) { closeSocket(sock); bridgeSocket = INVALID_SOCKET; return false; }
  bridgeReady = true;
  return true;
}
function sendstr(text: string): boolean {
  const bytes = Memory.allocUtf8String(text);
  const len = text.length;
  const head = Memory.alloc(4);
  head.writeU8((len >> 24) & 0xff); head.add(1).writeU8((len >> 16) & 0xff); head.add(2).writeU8((len >> 8) & 0xff); head.add(3).writeU8(len & 0xff);
  if (!sendAll(head, 4)) return false;
  return len > 0 ? sendAll(bytes, len) : true;
}
function sendAll(buffer: NativePointer, len: number): boolean { let sent = 0; while (sent < len) { const written = (socketSend(bridgeSocket, buffer.add(sent), len - sent, 0) as number); if (written <= 0) { bridgeReady = false; return false; } sent += written; } return true; }
function recvAll(buffer: NativePointer, len: number): boolean { let received = 0; while (received < len) { const chunk = (socketRecv(bridgeSocket, buffer.add(received), len - received, 0) as number); if (chunk <= 0) return false; received += chunk; } return true; }

let gameHost = "";
let readyToConnect = false;
function looksLikeGameHost(name: string): boolean { const lower = name.toLowerCase(); return lower.indexOf("game") === 0 && lower.indexOf("habbo") >= 0; }
function ensureConnected(): void {
  if (shuttingDown || bridgeReady || !readyToConnect) return;
  if (connectBridge(CLIENT_VERSION, gameHost)) log("[agent] G-Earth bridge connected host=" + (gameHost || "(unknown)"));
}
function captureHost(name: string | null): void {
  if (name && !gameHost && looksLikeGameHost(name)) { gameHost = name; readyToConnect = true; log("[host] game host " + name); ensureConnected(); }
}
function captureHostFromMemory(): void {
  if (gameHost) return;
  try {
    const objs = Il2Cpp.gc.choose(Il2Cpp.corlib.class("System.String"));
    for (let i = 0; i < objs.length; i++) {
      const handle = objs[i].handle;
      let value: string | null;
      try { const len = handle.add(8).readS32(); if (len < 12 || len > 200) continue; value = handle.add(12).readUtf16String(len); } catch (e) { continue; }
      if (!value || value.toLowerCase().indexOf("habbo") < 0) continue;
      const match = value.match(/^wss?:\/\/([^/:]+)/i);
      const candidate = match ? match[1] : value;
      const lower = candidate.toLowerCase();
      if (lower.indexOf("game") === 0 && lower.indexOf(".habbo") > 0 && candidate.indexOf("/") < 0) { gameHost = candidate; log("[host] resolved from memory " + candidate); return; }
    }
  } catch (e) { log("[host] memory scan failed " + e); }
}
try { const dns = winsock.findExportByName("GetAddrInfoW"); if (dns) Interceptor.attach(dns, { onEnter(args) { try { if (!args[0].isNull()) captureHost(args[0].readUtf16String()); } catch (e) {} } }); } catch (e) {}
try { const dns = winsock.findExportByName("getaddrinfo"); if (dns) Interceptor.attach(dns, { onEnter(args) { try { if (!args[0].isNull()) captureHost(args[0].readUtf8String()); } catch (e) {} } }); } catch (e) {}

let outCount = 0, inCount = 0;
let outBusy = false;
const frameHeader = Memory.alloc(6);
const readHeader = Memory.alloc(5);
const readBuffer = Memory.alloc(0x200000);
function sendFrameRaw(type: number, direction: number, bytes: number[]): boolean {
  if (!bridgeReady) return false;
  const len = bytes.length; const payload = Memory.alloc(len || 1);
  for (let index = 0; index < len; index++) payload.add(index).writeU8(bytes[index]);
  frameHeader.writeU8(type); frameHeader.add(1).writeU8(direction);
  frameHeader.add(2).writeU8((len >> 24) & 0xff); frameHeader.add(3).writeU8((len >> 16) & 0xff);
  frameHeader.add(4).writeU8((len >> 8) & 0xff); frameHeader.add(5).writeU8(len & 0xff);
  if (!sendAll(frameHeader, 6)) return false;
  if (len > 0 && !sendAll(payload, len)) return false;
  return true;
}
function sendFrame(type: number, direction: number, bytes: number[]): boolean { enterCriticalSection(criticalSection); try { return sendFrameRaw(type, direction, bytes); } finally { leaveCriticalSection(criticalSection); } }
function notify(direction: number, bytes: number[]): void { sendFrame(FRAME_NOTIFY, direction, bytes); }

function readFrame(): { tag: number; bytes: Uint8Array } | null {
  if (!recvAll(readHeader, 5)) return null;
  const tag = readHeader.readU8();
  const len = (readHeader.add(1).readU8() << 24) | (readHeader.add(2).readU8() << 16) | (readHeader.add(3).readU8() << 8) | readHeader.add(4).readU8();
  if (len < 0 || len > 0x200000) return null;
  if (len > 0 && !recvAll(readBuffer, len)) return null;
  return { tag, bytes: len > 0 ? new Uint8Array(readBuffer.readByteArray(len)!) : new Uint8Array(0) };
}
function queueInject(frame: Uint8Array): void {
  if (frame.length < 7) return;
  const direction = frame[0]; const packet: number[] = []; for (let index = 1; index < frame.length; index++) packet.push(frame[index]);
  if (packet.length < 6) return;
  const header = (packet[4] << 8) | packet[5];
  injectQueue.push({ direction, header, body: packet.slice(6) });
  log("[inject] queued from bridge dir=" + direction + " hdr=" + header + " bodyLen=" + (packet.length - 6));
}
function interceptOut(bytes: number[]): { blocked: boolean; bytes: number[] } | null {
  if (!bridgeReady || outBusy) { sendFrame(FRAME_NOTIFY, DIR_TO_SERVER, bytes); return null; }
  outBusy = true;
  enterCriticalSection(criticalSection);
  try {
    if (!sendFrameRaw(FRAME_INTERCEPT, DIR_TO_SERVER, bytes)) { bridgeReady = false; return null; }
    let guard = 0;
    for (; ;) {
      if (++guard > 4096) { bridgeReady = false; return null; }
      const frame = readFrame();
      if (!frame) { bridgeReady = false; return null; }
      if (frame.tag === TAG_INJECT) { queueInject(frame.bytes); continue; }
      if (frame.tag === TAG_VERDICT) { const payload = frame.bytes; const out: number[] = []; for (let index = 1; index < payload.length; index++) out.push(payload[index]); return { blocked: payload.length > 0 && payload[0] === 1, bytes: out }; }
    }
  } finally { leaveCriticalSection(criticalSection); outBusy = false; }
}

// il2cpp array offsets, 32 vs 64 bit
const pointerSize = Process.pointerSize;
const ARRAY_LENGTH_OFFSET = pointerSize === 8 ? 24 : 12;
const ARRAY_DATA_OFFSET = pointerSize === 8 ? 32 : 16;

function readArray(array: NativePointer, offset: number, length: number): number[] {
  const total = array.add(ARRAY_LENGTH_OFFSET).readS32();
  let available = (length && length > 0) ? Math.min(length, total - offset) : (total - offset);
  if (available < 4 || available > 2000000) return [];
  const lengthBytes = new Uint8Array(array.add(ARRAY_DATA_OFFSET + offset).readByteArray(4)!);
  const packetLength = (lengthBytes[0] << 24) | (lengthBytes[1] << 16) | (lengthBytes[2] << 8) | lengthBytes[3];
  // buffer is pooled and oversized, trim to the first frame
  let count = available;
  if (packetLength >= 2 && packetLength + 4 <= available) count = packetLength + 4;
  const result: number[] = [];
  const data = new Uint8Array(array.add(ARRAY_DATA_OFFSET + offset).readByteArray(count)!);
  for (let index = 0; index < count; index++) result.push(data[index]);
  return result;
}
function isHabbo(bytes: number[]): boolean {
  if (bytes.length < 6) return false;
  const len = ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
  return len >= 2 && len <= 0x200000;
}
function sameBytes(first: number[], second: number[]): boolean { if (first.length !== second.length) return false; for (let index = 0; index < first.length; index++) if ((first[index] & 0xff) !== (second[index] & 0xff)) return false; return true; }
function splitFrames(array: NativePointer, total: number): number[][] | null {
  const frames: number[][] = []; let position = 0;
  while (position + 4 <= total) {
    const frameLength = (array.add(ARRAY_DATA_OFFSET + position).readU8() << 24) | (array.add(ARRAY_DATA_OFFSET + position + 1).readU8() << 16) | (array.add(ARRAY_DATA_OFFSET + position + 2).readU8() << 8) | array.add(ARRAY_DATA_OFFSET + position + 3).readU8();
    if (frameLength < 2 || position + 4 + frameLength > total) return null;
    frames.push(readArray(array, position, 4 + frameLength));
    position += 4 + frameLength;
  }
  return position === total ? frames : null;
}

// recent cipher in/out pairs tagged by thread, used to recover the plaintext out header on the send thread
const cipherLog: { input: number; output: number; engine: NativePointer; threadId: number }[] = []; const CIPHER_LOG_MAX = 256;
function recordCipher(input: number, output: number, engine: NativePointer, threadId: number): void { cipherLog.push({ input, output, engine, threadId }); if (cipherLog.length > CIPHER_LOG_MAX) cipherLog.shift(); }
function findByOutput(output: number, threadId: number): { input: number; output: number; engine: NativePointer; threadId: number } | null { for (let index = cipherLog.length - 1; index >= 0; index--) { const record = cipherLog[index]; if (record.output === output && record.threadId === threadId) return record; } return null; }

const pendingIn: { cipherIn4: number; cipherIn5: number; bytes: number[] }[] = [];
let inHalf: { cipherIn: number; plain: number } | null = null;
let cipherActive = false;
function resolveInPair(first: { cipherIn: number; plain: number }, second: { cipherIn: number; plain: number }): number {
  for (let index = 0; index < pendingIn.length; index++) {
    const entry = pendingIn[index]; let plain4: number, plain5: number;
    if (entry.cipherIn5 === first.cipherIn && entry.cipherIn4 === second.cipherIn) { plain5 = first.plain; plain4 = second.plain; }
    else if (entry.cipherIn5 === second.cipherIn && entry.cipherIn4 === first.cipherIn) { plain5 = second.plain; plain4 = first.plain; }
    else continue;
    const header = (plain4 << 8) | plain5;
    if (header < 0 || header >= 6000) continue;
    entry.bytes[4] = plain4; entry.bytes[5] = plain5;
    return index;
  }
  return -1;
}
function resetCipherState(): void {
  outEngine = null; inEngine = null; cipherActive = false; inHalf = null; cipherLocked = false;
  cipherLog.length = 0; pendingIn.length = 0;
  log("[agent] relogin detected, cipher state reset");
}
function isHello(bytes: number[]): boolean {
  if (!(bytes.length >= 6 && bytes[4] === 0x0f && bytes[5] === 0xa0)) return false;
  for (let index = 6; index + 4 < bytes.length; index++) if (bytes[index] === 85 && bytes[index + 1] === 78 && bytes[index + 2] === 73 && bytes[index + 3] === 84 && bytes[index + 4] === 89) return true;
  return false;
}

// injection state
let cipherFn: NativeFunction<number, [NativePointerValue, number, NativePointerValue]> | null = null;
let cipherMethod: NativePointer = NULL;
let cipherLocked = false;
let outEngine: NativePointer | null = null;
let inEngine: NativePointer | null = null;
let outSendFn: NativeFunction<void, [NativePointerValue, NativePointerValue, NativePointerValue]> | null = null;
let outSendThis: NativePointer = NULL;
let outSendMethod: NativePointer = NULL;
let byteClass: Il2Cpp.Class | null = null;
let injecting = false;
let outThreadId = 0;
let instantInject = false;
const injectQueue: { direction: number; header: number; body: number[] }[] = [];

function encryptByte(value: number): number { try { return (cipherFn!(outEngine!, value, cipherMethod) as number) & 0xff; } catch (e) { return value; } }
function buildOutPacket(header: number, body: number[]): number[] {
  const len = 2 + body.length;
  const packet = new Array(4 + len);
  packet[0] = (len >>> 24) & 0xff; packet[1] = (len >>> 16) & 0xff; packet[2] = (len >>> 8) & 0xff; packet[3] = len & 0xff;
  packet[4] = (header >>> 8) & 0xff; packet[5] = header & 0xff;
  for (let index = 0; index < body.length; index++) packet[6 + index] = body[index] & 0xff;
  const enc5 = encryptByte(packet[5]), enc4 = encryptByte(packet[4]); packet[5] = enc5; packet[4] = enc4;
  return packet;
}
function injectToServer(header: number, body: number[]): boolean {
  injecting = true;
  try {
    const bytes = buildOutPacket(header, body);
    const byteArray = Il2Cpp.array<number>(byteClass!, bytes);
    outSendFn!(outSendThis, byteArray.handle, outSendMethod);
    return true;
  } catch (e) { logErr("inject-server", e); return false; }
  finally { injecting = false; }
}

const ioctlResult = Memory.alloc(4), injectHeader = Memory.alloc(5), injectBuffer = Memory.alloc(0x40000);
function pollInjects(): void {
  if (!bridgeReady) return;
  for (let guard = 0; guard < 64; guard++) {
    if ((ioctlSocket(bridgeSocket, FIONREAD, ioctlResult) as number) !== 0) return;
    const available = ioctlResult.readU32();
    if (available < 5) return;
    if ((socketRecv(bridgeSocket, injectHeader, 5, 2) as number) !== 5) return;
    const tag = injectHeader.readU8();
    const len = (injectHeader.add(1).readU8() << 24) | (injectHeader.add(2).readU8() << 16) | (injectHeader.add(3).readU8() << 8) | injectHeader.add(4).readU8();
    if (len < 0 || len > 0x40000) { bridgeReady = false; return; }
    if (available < 5 + len) return;
    if (!recvAll(injectHeader, 5)) { bridgeReady = false; return; }
    if (len > 0 && !recvAll(injectBuffer, len)) { bridgeReady = false; return; }
    if (tag === TAG_INJECT) { const frame = len > 0 ? new Uint8Array(injectBuffer.readByteArray(len)!) : new Uint8Array(0); queueInject(frame); }
  }
}
function flushInjects(): void {
  if (!injectQueue.length) return;
  let queued: { direction: number; header: number; body: number[] } | undefined;
  while ((queued = injectQueue.shift())) {
    if (queued.direction === DIR_TO_SERVER) { if (byteClass && outEngine && cipherFn) injectToServer(queued.header, queued.body); }
    else toClientQueue.push({ header: queued.header, body: queued.body });
  }
}

function interceptBatch(array: NativePointer, total: number, frames: number[][], args: InvocationArguments): void {
  const outFrames: number[][] = []; let changed = false;
  const sendThread = Process.getCurrentThreadId();
  for (const frame of frames) {
    const match4 = findByOutput(frame[4], sendThread), match5 = findByOutput(frame[5], sendThread);
    if (!(match4 && match5)) { if (bridgeReady && ((frame[4] << 8) | frame[5]) <= MAX_HEADER) notify(DIR_TO_SERVER, frame); outFrames.push(frame); continue; }
    const keystream4 = frame[4] ^ match4.input, keystream5 = frame[5] ^ match5.input;
    const plain = frame.slice(); plain[4] = match4.input; plain[5] = match5.input;
    if (!outEngine) { outEngine = match4.engine; }
    outCount++;
    let outFrame = frame;
    if (bridgeReady && cipherActive) {
      const verdict = interceptOut(plain);
      if (verdict) {
        if (verdict.blocked) { const blockedReply = [0, 0, 0, 2, 0, 196]; blockedReply[4] ^= keystream4; blockedReply[5] ^= keystream5; outFrame = blockedReply; changed = true; }
        else if (verdict.bytes && verdict.bytes.length >= 6 && isHabbo(verdict.bytes) && !sameBytes(verdict.bytes, plain)) { const rewritten = verdict.bytes.slice(); rewritten[4] ^= keystream4; rewritten[5] ^= keystream5; outFrame = rewritten; changed = true; }
      }
    } else if (bridgeReady) { notify(DIR_TO_SERVER, plain); }
    outFrames.push(outFrame);
  }
  let extra: number[] = [];
  if (injectQueue.length && byteClass && outEngine && cipherFn) {
    injecting = true;
    try { let queued: { direction: number; header: number; body: number[] } | undefined; while ((queued = injectQueue.shift())) { if (queued.direction === DIR_TO_SERVER) extra = extra.concat(buildOutPacket(queued.header, queued.body)); else toClientQueue.push({ header: queued.header, body: queued.body }); } }
    catch (e) { logErr("batch-inject", e); } finally { injecting = false; }
  }
  if (!changed && extra.length === 0) return;
  let combined: number[] = [];
  for (const outFrame of outFrames) combined = combined.concat(outFrame);
  if (extra.length) combined = combined.concat(extra);
  if (combined.length <= total) {
    for (let index = 0; index < combined.length; index++) array.add(ARRAY_DATA_OFFSET + index).writeU8(combined[index]);
    for (let index = combined.length; index < total; index++) array.add(ARRAY_DATA_OFFSET + index).writeU8(0);
  } else {
    const byteArray = Il2Cpp.array<number>(byteClass!, combined);
    args[1] = byteArray.handle;
  }
}

// toclient inject, fresh reader with the cipher nulled so the plaintext header survives, then dispatch
const toClientQueue: { header: number; body: number[] }[] = [];
let toClientReaderClass: Il2Cpp.Class | null = null;
let toClientCtor: NativeFunction<void, [NativePointerValue, NativePointerValue, NativePointerValue, NativePointerValue]> | null = null;
let toClientRecv: NativeFunction<void, [NativePointerValue, NativePointerValue, number, number, NativePointerValue]> | null = null;
let toClientDispatch: NativeFunction<void, [NativePointerValue, NativePointerValue]> | null = null;
let toClientCipherOffset = -1, toClientCtorArg0Offset = -1, toClientCtorArg1Offset = -1;
let toClientCtorArg0: NativePointer = NULL, toClientCtorArg1: NativePointer = NULL;
let toClientDispatchTarget: NativePointer = NULL;
let toClientByteClass: Il2Cpp.Class | null = null;
let toClientReady = false, toClientInjecting = false;
const rvaOf = (address: NativePointer) => "0x" + address.sub(Il2Cpp.module.base).toString(16);

function findReaderCtor(klass: Il2Cpp.Class): { address: NativePointer; param0Type: string; param1Type: string } | null {
  for (const method of klass.methods) {
    if (method.name === ".ctor" && method.parameterCount === 2) {
      const param0Type = method.parameters[0].type.name, param1Type = method.parameters[1].type.name;
      if (param0Type.indexOf("System.") !== 0 && param1Type.indexOf("System.") !== 0) { let address: NativePointer; try { address = method.virtualAddress; } catch (e) { continue; } if (!address.isNull()) return { address, param0Type, param1Type }; }
    }
  }
  return null;
}
function fieldOffsetByType(klass: Il2Cpp.Class, typeName: string): number {
  for (const field of klass.fields) if (field.type.name === typeName) return field.offset;
  return -1;
}
function setupToClient(cand: Cand): boolean {
  try {
    toClientRecv = new NativeFunction(cand.address, "void", ["pointer", "pointer", "int", "int", "pointer"]);
    const ctor = findReaderCtor(cand.klass); if (!ctor) { logErr("tc-setup", "no reader ctor"); return false; }
    toClientCtor = new NativeFunction(ctor.address, "void", ["pointer", "pointer", "pointer", "pointer"]);
    toClientCipherOffset = cand.cipherOffset;
    toClientCtorArg0Offset = fieldOffsetByType(cand.klass, ctor.param0Type);
    toClientCtorArg1Offset = fieldOffsetByType(cand.klass, ctor.param1Type);
    if (toClientCtorArg0Offset < 0 || toClientCtorArg1Offset < 0) { logErr("tc-setup", "field offset not found"); return false; }
    toClientReaderClass = cand.klass;
    log("[tc] recvFn " + rvaOf(cand.address) + " ctorFn " + rvaOf(ctor.address) + " cipherOff 0x" + toClientCipherOffset.toString(16) + " regOff 0x" + toClientCtorArg0Offset.toString(16) + " arg2Off 0x" + toClientCtorArg1Offset.toString(16));
    return true;
  } catch (e) { logErr("tc-setup", e); return false; }
}
function injectToClient(header: number, body: number[]): void {
  if (!toClientReady || shuttingDown) return;
  toClientInjecting = true;
  try {
    const fresh = toClientReaderClass!.alloc();
    toClientCtor!(fresh.handle, toClientCtorArg0, toClientCtorArg1, NULL);
    fresh.handle.add(toClientCipherOffset).writePointer(NULL);
    const len = 2 + body.length;
    const packet = [(len >>> 24) & 0xff, (len >>> 16) & 0xff, (len >>> 8) & 0xff, len & 0xff, (header >>> 8) & 0xff, header & 0xff].concat(body);
    const byteArray = Il2Cpp.array<number>(toClientByteClass!, packet);
    toClientRecv!(fresh.handle, byteArray.handle, 0, packet.length, NULL);
    toClientDispatch!(fresh.handle, toClientDispatchTarget);
    log("[tc] injected toclient hdr=" + header + " len=" + packet.length + " tid=" + Process.getCurrentThreadId());
  } catch (e) { logErr("tc-inject", e); } finally { toClientInjecting = false; }
}
function findToClientDispatch(returnAddress: NativePointer): boolean {
  try {
    let cursor = returnAddress;
    const base = Il2Cpp.module.base, size = Il2Cpp.module.size;
    for (let step = 0; step < 48; step++) {
      const instruction = Instruction.parse(cursor);
      if (instruction.mnemonic === "call") {
        const match = instruction.opStr.match(/0x[0-9a-f]+/i);
        if (match) { const target = ptr(match[0]); if (target.compare(base) > 0 && target.compare(base.add(size)) < 0) { toClientDispatch = new NativeFunction(target, "void", ["pointer", "pointer"]); log("[tc] dispatchFn " + rvaOf(target)); hookDispatch(target); return true; } }
      }
      if (instruction.mnemonic === "ret") break; cursor = instruction.next;
    }
  } catch (e) { logErr("tc-disc", e); }
  return false;
}
function hookDispatch(address: NativePointer): void {
  Interceptor.attach(address, {
    onEnter(args) {
      if (toClientInjecting || toClientReady) return;
      try {
        const reader = args[0]; toClientDispatchTarget = args[1]; toClientReaderClass = new Il2Cpp.Object(reader).class;
        toClientCtorArg0 = reader.add(toClientCtorArg0Offset).readPointer(); toClientCtorArg1 = reader.add(toClientCtorArg1Offset).readPointer();
        if (!toClientDispatchTarget.isNull() && !toClientCtorArg0.isNull() && toClientByteClass) { toClientReady = true; log("[tc] ready, toclient inject armed"); }
      } catch (e) { logErr("tc-cap", e); }
    }
  });
}

let errorCount = 0;
function logErr(tag: string, error: unknown): void { if (errorCount < 40) { errorCount++; log("[err:" + tag + "] " + error); } }
let shuttingDown = false;
const timers: any[] = [];
function every(ms: number, callback: () => void): any { const timerId = setInterval(callback, ms); timers.push(timerId); return timerId; }
function teardown(why: string): void {
  if (shuttingDown) return; shuttingDown = true;
  try { log("[agent] teardown (" + why + ") clean exit"); } catch (e) {}
  try { timers.forEach(timerId => { try { clearInterval(timerId); } catch (e) {} }); } catch (e) {}
  try { if (bridgeSocket !== INVALID_SOCKET) { closeSocket(bridgeSocket); bridgeSocket = INVALID_SOCKET; bridgeReady = false; } } catch (e) {}
  try { Interceptor.detachAll(); } catch (e) {}
  try { terminateProcess(getCurrentProcess(), 0); } catch (e) {}
}
let inCipher = 0;

interface Cand { address: NativePointer; handle: NativePointer; klass: Il2Cpp.Class; className: string; cipherOffset: number; }
function inModule(address: NativePointer): boolean { const base = Il2Cpp.module.base; return address.compare(base) >= 0 && address.compare(base.add(Il2Cpp.module.size)) < 0; }
function cipherFieldOffset(klass: Il2Cpp.Class, cipherNames: Set<string>): number { for (const field of klass.fields) if (cipherNames.has(field.type.name)) return field.offset; return -1; }

function discover() {
  const cipher: Cand[] = [], outAll: Cand[] = [], inAll: Cand[] = [], outSend: Cand[] = [];
  const cipherNames = new Set<string>();
  for (const assembly of Il2Cpp.domain.assemblies) {
    for (const klass of assembly.image.classes) {
      let methods: Il2Cpp.Method[]; try { methods = klass.methods; } catch (e) { continue; }
      for (const method of methods) {
        const returnType = method.returnType.name; const paramCount = method.parameterCount;
        const isByteToByte = returnType === "System.Byte" && paramCount === 1 && method.parameters[0].type.name === "System.Byte";
        if (isByteToByte) cipherNames.add(klass.name);
        if (method.isStatic) continue;
        let address: NativePointer; try { address = method.virtualAddress; } catch (e) { continue; }
        if (address.isNull() || !inModule(address)) continue;
        const cand: Cand = { address, handle: method.handle, klass, className: klass.name, cipherOffset: -1 };
        if (isByteToByte) cipher.push(cand);
        else if (returnType === "System.Byte[]" && paramCount === 0) outAll.push(cand);
        else if (returnType === "System.Void" && paramCount === 3 && method.parameters[0].type.name === "System.Byte[]" && method.parameters[1].type.name === "System.Int32" && method.parameters[2].type.name === "System.Int32") inAll.push(cand);
        else if (returnType === "System.Void" && paramCount === 1 && method.parameters[0].type.name === "System.Byte[]") outSend.push(cand);
      }
    }
  }
  const outCand = outAll.map(cand => { const offset = cipherFieldOffset(cand.klass, cipherNames); if (offset < 0) return null; cand.cipherOffset = offset; return cand; }).filter(Boolean) as Cand[];
  const inCand = inAll.map(cand => { const offset = cipherFieldOffset(cand.klass, cipherNames); if (offset < 0) return null; cand.cipherOffset = offset; return cand; }).filter(Boolean) as Cand[];
  return { cipher, outCand, inCand, outSend, cipherNames };
}

const AGENT_VERSION = "unity-standalone 1.0";
function main(): void {
  log("[agent] " + AGENT_VERSION + " loaded, bridge port " + bridgeport);

  try { const fn = winsock.getExportByName("WSACleanup"); if (fn) Interceptor.attach(fn, { onEnter() { teardown("WSACleanup"); } }); } catch (e) {}
  try { const fn = Il2Cpp.module.getExportByName("il2cpp_shutdown"); if (fn) Interceptor.attach(fn, { onEnter() { teardown("il2cpp_shutdown"); } }); } catch (e) {}

  try {
    const user32 = Module.load("user32.dll");
    ["PeekMessageW", "PeekMessageA"].forEach(name => {
      const fn = user32.findExportByName(name); if (fn) Interceptor.attach(fn, {
        onEnter() {
          if (shuttingDown || injecting || !outThreadId) return;
          if (Process.getCurrentThreadId() !== outThreadId) return;
          if (!instantInject) { instantInject = true; log("[inject] instant path active (per-frame on OUT thread)"); }
          try { pollInjects(); if (injectQueue.length) flushInjects(); if (toClientReady && toClientQueue.length) { let queued: { header: number; body: number[] } | undefined; while ((queued = toClientQueue.shift())) injectToClient(queued.header, queued.body); } } catch (e) { logErr("peek", e); }
        }
      });
    });
  } catch (e) {}

  const discovered = discover();
  byteClass = Il2Cpp.corlib.class("System.Byte");
  toClientByteClass = byteClass;
  log("[disc] cipher=" + discovered.cipher.length + " outCand=" + discovered.outCand.length + " inCand=" + discovered.inCand.length + " outSend=" + discovered.outSend.length);
  if (discovered.inCand.length) setupToClient(discovered.inCand[0]);

  if (discovered.cipher.length) { cipherFn = new NativeFunction(discovered.cipher[0].address, "int", ["pointer", "int", "pointer"]); cipherMethod = discovered.cipher[0].handle; }

  discovered.cipher.forEach(cand => {
    Interceptor.attach(cand.address, {
      onEnter(args) { (this as any).inputByte = args[1].toInt32() & 0xff; (this as any).engine = args[0]; },
      onLeave(ret) {
        if (shuttingDown || injecting) return;
        try {
          const output = ret.toInt32() & 0xff; cipherActive = true;
          const engine = (this as any).engine as NativePointer;
          const isInbound = outEngine ? !engine.equals(outEngine) : false;
          if (isInbound && !inEngine) { inEngine = engine; log("[eng] inEngine locked " + engine + " tid=" + Process.getCurrentThreadId()); }
          if (!isInbound) recordCipher((this as any).inputByte, output, engine, Process.getCurrentThreadId());
          // lock the cipher method that runs on the out engine, discovery can pick a different byte(byte)
          if (!isInbound && outEngine && !cipherLocked) { cipherFn = new NativeFunction(cand.address, "int", ["pointer", "int", "pointer"]); cipherMethod = cand.handle; cipherLocked = true; log("[eng] out cipher locked " + rvaOf(cand.address)); }
          if (isInbound && (outEngine || inEngine)) {
            inCipher++;
            if (!inHalf) { inHalf = { cipherIn: (this as any).inputByte, plain: output }; }
            else {
              const pair = { cipherIn: (this as any).inputByte, plain: output };
              const matchedIndex = resolveInPair(inHalf, pair);
              if (matchedIndex >= 0) { const entry = pendingIn[matchedIndex]; pendingIn.splice(matchedIndex, 1); inCount++; try { notify(DIR_TO_CLIENT, entry.bytes); } catch (e) {} inHalf = null; }
              else { inHalf = pair; }
            }
          }
        } catch (e) { logErr("cipher", e); }
      }
    });
  });

  let boundOutSend: NativePointer | null = null;
  discovered.outSend.forEach(cand => {
    try {
      Interceptor.attach(cand.address, {
        onEnter(args) {
          if (shuttingDown || injecting) return;
          try {
            if (boundOutSend && !cand.address.equals(boundOutSend)) return;
            const array = args[1]; if (array.isNull()) return;
            const total = array.add(ARRAY_LENGTH_OFFSET).readS32(); if (total < 6 || total > 2000000) return;
            const declaredLen = (array.add(ARRAY_DATA_OFFSET).readU8() * 0x1000000) + (array.add(ARRAY_DATA_OFFSET + 1).readU8() << 16) + (array.add(ARRAY_DATA_OFFSET + 2).readU8() << 8) + array.add(ARRAY_DATA_OFFSET + 3).readU8();
            if (declaredLen < 2 || declaredLen > 0x200000) return;
            const bytes = readArray(array, 0, 0);
            if (!isHabbo(bytes)) return;
            if (!readyToConnect) { readyToConnect = true; if (gameHost) ensureConnected(); else setTimeout(() => Il2Cpp.perform(() => { captureHostFromMemory(); ensureConnected(); }), 0); }
            if (!boundOutSend) { boundOutSend = cand.address; outSendFn = new NativeFunction(cand.address, "void", ["pointer", "pointer", "pointer"]); outThreadId = Process.getCurrentThreadId(); log("[OUT bound] " + cand.className + " tid=" + outThreadId); }
            outSendThis = args[0]; outSendMethod = args[2];
            if (isHello(bytes)) resetCipherState();
            if (bytes.length < total) { const frames = splitFrames(array, total); if (frames && frames.length > 1) { interceptBatch(array, total, frames, args); return; } }
            outCount++;
            const sendThread = Process.getCurrentThreadId();
            const record4 = findByOutput(bytes[4], sendThread), record5 = findByOutput(bytes[5], sendThread);
            let keystream4 = 0, keystream5 = 0, havePlain = false;
            if (record4 && record5) { keystream4 = bytes[4] ^ record4.input; keystream5 = bytes[5] ^ record5.input; bytes[4] = record4.input; bytes[5] = record5.input; havePlain = true; if (!outEngine) { outEngine = record4.engine; log("[eng] outEngine locked " + record4.engine + " tid=" + Process.getCurrentThreadId()); } }
            if (debugHeaders) log("[OUT] hdr=" + ((bytes[4] << 8) | bytes[5]) + " matched=" + havePlain + " len=" + total);
            let finalPlain: number[] | null = null;
            if (havePlain && bridgeReady && cipherActive) {
              const verdict = interceptOut(bytes);
              if (verdict) { if (verdict.blocked) { finalPlain = [0, 0, 0, 2, 0, 196]; } else if (verdict.bytes && verdict.bytes.length >= 6 && isHabbo(verdict.bytes) && !sameBytes(verdict.bytes, bytes)) { finalPlain = verdict.bytes.slice(); } }
            } else if (bridgeReady) { if (((bytes[4] << 8) | bytes[5]) <= MAX_HEADER) notify(DIR_TO_SERVER, bytes); }
            // flush injects on the out thread. peekmessage path is just faster when it fires
            let extra: number[] = [];
            if (injectQueue.length && byteClass && outEngine && cipherFn) {
              injecting = true;
              try { let queued: { direction: number; header: number; body: number[] } | undefined; while ((queued = injectQueue.shift())) { if (queued.direction === DIR_TO_SERVER) extra = extra.concat(buildOutPacket(queued.header, queued.body)); else toClientQueue.push({ header: queued.header, body: queued.body }); } } catch (e) { logErr("inject", e); } finally { injecting = false; }
            }
            if (toClientReady && toClientQueue.length) { let queued: { header: number; body: number[] } | undefined; while ((queued = toClientQueue.shift())) injectToClient(queued.header, queued.body); }
            if (finalPlain === null && extra.length === 0) return;
            const originalLength = bytes.length;
            let headBytes: number[];
            if (finalPlain !== null) { headBytes = finalPlain.slice(); if (headBytes.length >= 6) { headBytes[4] ^= keystream4; headBytes[5] ^= keystream5; } }
            else { headBytes = readArray(array, 0, originalLength); }
            const combined = extra.length ? headBytes.concat(extra) : headBytes;
            if (combined.length <= total) {
              for (let index = 0; index < combined.length; index++) array.add(ARRAY_DATA_OFFSET + index).writeU8(combined[index]);
              for (let index = combined.length; index < total; index++) array.add(ARRAY_DATA_OFFSET + index).writeU8(0);
            } else { const byteArray = Il2Cpp.array<number>(byteClass!, combined); args[1] = byteArray.handle; }
          } catch (e) {}
        }
      });
    } catch (e) {}
  });

  let inHookLogged = false;
  discovered.inCand.forEach(cand => {
    Interceptor.attach(cand.address, {
      onEnter(args) {
        if (shuttingDown) return;
        try {
          const array = args[1], offset = args[2].toInt32(), length = args[3].toInt32();
          if (array.isNull() || length < 6) return;
          if (toClientRecv && !toClientDispatch) findToClientDispatch((this as any).returnAddress);
          const bytes = readArray(array, offset, length);
          if (!isHabbo(bytes)) return;
          if (!inHookLogged) { inHookLogged = true; log("[IN bound] " + cand.className + " tid=" + Process.getCurrentThreadId()); }
          if (!cipherActive) { if (((bytes[4] << 8) | bytes[5]) <= MAX_HEADER) { inCount++; notify(DIR_TO_CLIENT, bytes); } return; }
          pendingIn.push({ cipherIn4: bytes[4], cipherIn5: bytes[5], bytes });
          if (pendingIn.length > 96) { pendingIn.shift(); logErr("pendIn-cap", "dropped oldest"); }
        } catch (e) { logErr("inrecv", e); }
      }
    });
  });

  ensureConnected();
  every(2000, ensureConnected);
  log("[agent] ready (" + (bridgeReady ? "bridge connected" : "waiting for G-Earth bridge") + ")");

  let lastOut = 0, lastIn = 0;
  every(5000, () => {
    if (shuttingDown) return;
    log("[stat] out=" + outCount + "(+" + (outCount - lastOut) + ") in=" + inCount + "(+" + (inCount - lastIn) + ") inCipher=" + inCipher + " ge=" + (bridgeReady ? 1 : 0) + " cipher=" + (cipherActive ? 1 : 0) + " outEng=" + (outEngine ? 1 : 0) + " inEng=" + (inEngine ? 1 : 0) + " pendIn=" + pendingIn.length + " injQ=" + injectQueue.length);
    lastOut = outCount; lastIn = inCount;
  });
}

Il2Cpp.perform(() => { setTimeout(() => { try { main(); } catch (e: any) { log("[agent] ERR " + e + "\n" + (e && e.stack)); } }, 800); });
