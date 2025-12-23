import os
import sys
import json
import importlib.util
import tracemalloc
from http.server import BaseHTTPRequestHandler
from socketserver import UnixStreamServer

SOCK_PATH = os.environ.get('SOCK_PATH', '/var/run/function.sock')
USER_CODE_PATH = '/var/task/index.py'

user_handler = None

def load_user_code():
    global user_handler
    if not os.path.exists(USER_CODE_PATH):
        print(f"Error: User code not found at {USER_CODE_PATH}", file=sys.stderr)
        sys.exit(1)
    
    try:
        spec = importlib.util.spec_from_file_location("user_module", USER_CODE_PATH)
        user_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(user_module)
        
        if hasattr(user_module, 'handler'):
            user_handler = user_module.handler
            print(f"User function 'handler' loaded from {USER_CODE_PATH}")
        else:
            raise Exception("Function 'handler' not found in index.py")
            
    except Exception as e:
        print(f"Failed to load user code: {e}", file=sys.stderr)
        sys.exit(1)

class FunctionHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        response_proto = {
            "result": None,
            "success": False,
            "memory_usage": 0,
            "error_message": None
        }
        
        tracemalloc.start()
        
        # [수정] 변수 초기화
        request_id = 'unknown'

        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            
            # Envelope 파싱
            envelope = json.loads(body) if body else {}
            meta = envelope.get("system_metadata", {})
            user_params = envelope.get("user_params", {})
            
            request_id = meta.get("request_id", "unknown")
            
            # [추가] 시작 로그 마커 (파이썬은 flush=True 필수)
            print(f"===LOG_START:{request_id}===", flush=True)
            
            # 사용자 함수 실행
            result = user_handler(user_params)
            
            if isinstance(result, (dict, list)):
                response_proto["result"] = json.dumps(result)
            else:
                response_proto["result"] = str(result)
            
            response_proto["success"] = True
            
        except Exception as e:
            print(f"Execution Error: {e}", file=sys.stderr, flush=True)
            response_proto["success"] = False
            response_proto["error_message"] = str(e)
            
        finally:
            _, peak = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            response_proto["memory_usage"] = peak
            
            # [추가] 종료 로그 마커
            print(f"===LOG_END:{request_id}===", flush=True)
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response_proto).encode('utf-8'))

    def log_message(self, format, *args):
        pass

if __name__ == "__main__":
    load_user_code()
    
    if os.path.exists(SOCK_PATH):
        os.remove(SOCK_PATH)
        
    with UnixStreamServer(SOCK_PATH, FunctionHandler) as server:
        os.chmod(SOCK_PATH, 0o777)
        print(f"Python Runtime listening on unix:{SOCK_PATH}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass