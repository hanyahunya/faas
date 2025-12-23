const http = require('http');
const fs = require('fs');

const SOCK_PATH = process.env.SOCK_PATH || '/var/run/function.sock';
const USER_CODE_PATH = '/var/task/index.js';

let userFunction;
try {
    if (fs.existsSync(USER_CODE_PATH)) {
        const userModule = require(USER_CODE_PATH);
        
        if (typeof userModule === 'function') {
            userFunction = userModule;
        } 
        else if (typeof userModule.handler === 'function') {
            userFunction = userModule.handler;
        } else {
            throw new Error("No valid handler found (module.exports or exports.handler)");
        }
        console.log('User function loaded.');
    } else {
        throw new Error(`User code not found at ${USER_CODE_PATH}`);
    }
} catch (e) {
    console.error('Failed to load user function:', e);
    process.exit(1);
}

const server = http.createServer(async (req, res) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', async () => {
        const responseProto = {
            result: null,
            success: false,
            memory_usage: 0,
            error_message: null
        };
        
        const startMem = process.memoryUsage().heapUsed;
        
        // [수정] Envelope 파싱 및 변수 선언
        let requestId = 'unknown';
        let userParams = {};

        try {
            // Body가 Envelope 형태(system_metadata, user_params)라고 가정
            const envelope = body ? JSON.parse(body) : {};
            
            const meta = envelope.system_metadata || {};
            userParams = envelope.user_params || {}; // 사용자 함수에는 이 params만 전달
            
            requestId = meta.request_id || 'unknown';

            // [추가] 시작 로그 마커
            console.log(`===LOG_START:${requestId}===`);

            // 사용자 함수 실행
            const result = await userFunction(userParams);

            responseProto.result = (typeof result === 'object') ? JSON.stringify(result) : String(result);
            responseProto.success = true;

        } catch (e) {
            console.error('Execution Error:', e);
            responseProto.success = false;
            responseProto.error_message = e.message || String(e);
        } finally {
            const endMem = process.memoryUsage().heapUsed;
            responseProto.memory_usage = Math.max(0, endMem - startMem);

            // [추가] 종료 로그 마커
            console.log(`===LOG_END:${requestId}===`);

            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(responseProto));
        }
    });
});

if (fs.existsSync(SOCK_PATH)) fs.unlinkSync(SOCK_PATH);

server.listen(SOCK_PATH, () => {
    fs.chmodSync(SOCK_PATH, '0777');
    console.log(`Node Runtime listening on unix:${SOCK_PATH}`);
});

process.on('SIGINT', () => { server.close(); process.exit(); });