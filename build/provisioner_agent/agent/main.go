package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
	"github.com/docker/docker/pkg/stdcopy"
	"google.golang.org/grpc"

	pb "my-agent/proto"
)

// ==========================================
// 1. 설정 및 상수
// ==========================================

const (
	// [수정됨] S3 로그 저장 기능 On/Off 스위치 (현재 꺼짐)
	EnableS3LogUpload = false

	// 로그 수집 시 최대 허용 크기 (1MB) - OOM 방지
	MaxLogSize = 1 * 1024 * 1024

	// [삭제됨] IdleTimeout은 더 이상 사용하지 않음
	// IdleTimeout = 5 * time.Second

	// [Cgroup v2 상수]
	CgroupV2Frozen = "1"
	CgroupV2Thawed = "0"
)

// 디버그 모드 여부 (환경변수로 제어하여 불필요한 I/O 최소화)
var debugMode = os.Getenv("DEBUG_MODE") == "true"

func debugLog(format string, v ...interface{}) {
	if debugMode {
		log.Printf(format, v...)
	}
}

// Runtime 응답 구조체
type RuntimeResponse struct {
	Result       string `json:"result"`
	Success      bool   `json:"success"`
	MemoryUsage  int64  `json:"memory_usage"`
	ErrorMessage string `json:"error_message"`
}

// ==========================================
// 2. 상태 관리 (Concurrency Control)
// ==========================================

// 컨테이너별 상태 (Mutex 보호)
type ContainerState struct {
	mu         sync.Mutex
	// [삭제됨] timer, isPaused 모두 제거 (상태 확인 없이 무조건 실행하므로 불필요)
	cgroupPath string // [추가] Cgroup 파일 경로 캐싱
}

type server struct {
	pb.UnimplementedAgentServiceServer
	dockerClient *client.Client
	s3Client     *s3.Client
	logBucket    string

	// 인스턴스별 상태 맵 (Thread-Safe)
	instanceStates sync.Map // key: containerName, value: *ContainerState
}

// [유틸리티] 파일에 직접 써서 Cgroup 제어 (Docker 데몬 우회)
func updateCgroupState(cgroupPath string, state string) error {
	// "Blind Write": 상태 확인 없이 덮어씀 (가장 빠름, 시스템 콜 1회)
	// 0644 권한으로 파일 쓰기
	return os.WriteFile(cgroupPath, []byte(state), 0644)
}

// 상태 객체 가져오기 또는 초기화 (최초 1회 경로 탐색 포함)
func (s *server) getContainerState(ctx context.Context, containerName string) (*ContainerState, error) {
	// 1. 캐시된 상태가 있으면 반환
	if val, ok := s.instanceStates.Load(containerName); ok {
		return val.(*ContainerState), nil
	}

	// 2. 없으면 초기화 (Docker API로 ID 조회 필요)
	// 실제 컨테이너가 떠있어야 ID를 알 수 있음
	resp, err := s.dockerClient.ContainerInspect(ctx, containerName)
	if err != nil {
		return nil, fmt.Errorf("failed to inspect container: %w", err)
	}

	// [중요] Cgroup v2 경로 구성 (Ubuntu Systemd 드라이버 기준)
	// 예: /sys/fs/cgroup/system.slice/docker-{LongID}.scope/cgroup.freeze
	path := fmt.Sprintf("/sys/fs/cgroup/system.slice/docker-%s.scope/cgroup.freeze", resp.ID)

	// 만약 경로가 없으면(다른 리눅스 배포판 등), 기본 cgroupfs 경로 시도
	if _, err := os.Stat(path); os.IsNotExist(err) {
		fallbackPath := fmt.Sprintf("/sys/fs/cgroup/docker/%s/cgroup.freeze", resp.ID)
		if _, err := os.Stat(fallbackPath); err == nil {
			path = fallbackPath
		} else {
			// 경로를 못 찾으면 로그 남기고 일단 진행 (나중에 에러 날 것임)
			log.Printf("[Warning] Cannot find cgroup path for %s. Tried: %s", containerName, path)
		}
	}

	newState := &ContainerState{
		// [수정] isPaused 초기값 설정 불필요
		cgroupPath: path,
	}

	s.instanceStates.Store(containerName, newState)
	return newState, nil
}

// ==========================================
// 3. gRPC 실행 로직 (핵심)
// ==========================================

func (s *server) Execute(ctx context.Context, req *pb.ExecuteRequest) (*pb.ExecuteResponse, error) {
	// [최적화] 디버그 모드가 아니면 요청 수신 로그 생략
	debugLog("[Req: %s] 요청 수신. Path: %s", req.RequestId, req.SockPath)

	parts := strings.Split(req.SockPath, "/")
	if len(parts) < 3 {
		return &pb.ExecuteResponse{Success: false, ErrorMessage: "Invalid socket path"}, nil
	}
	functionId := parts[1]
	instanceId := parts[2]
	containerName := "ins-" + instanceId

	// -------------------------------------------------------
	// [Step 1] Warm Start 처리 (Direct Unpause)
	// -------------------------------------------------------
	// getContainerState가 이제 error를 반환하므로 처리 필요
	state, err := s.getContainerState(ctx, containerName)
	if err != nil {
		// 컨테이너를 찾을 수 없으면 에러 반환
		return &pb.ExecuteResponse{Success: false, ErrorMessage: "Container not found: " + err.Error()}, nil
	}

	state.mu.Lock()

	// [수정] 타이머 취소 및 isPaused 확인 로직 제거
	// 무조건 Unpause(Thawed) 실행
	if err := updateCgroupState(state.cgroupPath, CgroupV2Thawed); err != nil {
		log.Printf("[Req: %s] Direct Unpause Failed: %v", req.RequestId, err)
	} else {
		debugLog("[Req: %s] Direct Unpause (THAWED)", req.RequestId)
	}
	
	state.mu.Unlock()

	// -------------------------------------------------------
	// [Step 2] 실행 (Runtime 호출)
	// -------------------------------------------------------
	logSince := time.Now() // 로그 수집 기준점
	fullSocketPath := "/ws/" + req.SockPath

	// Envelope 생성
	requestBodyValue := map[string]interface{}{
		"system_metadata": map[string]string{
			"request_id": req.RequestId,
		},
		"user_params": json.RawMessage(req.InputPayload),
	}
	jsonBytes, _ := json.Marshal(requestBodyValue)

	startTime := time.Now()
	runtimeResp, err := callRuntimeViaUDS(fullSocketPath, jsonBytes)
	duration := time.Since(startTime).Milliseconds()

	// -------------------------------------------------------
	// [Step 3] 실행 종료 후 즉시 정지 (Direct Pause)
	// -------------------------------------------------------
	state.mu.Lock()
	
	// [수정] 타이머 예약 및 isPaused 확인 로직 제거
	// 무조건 Pause(Frozen) 실행
	if err := updateCgroupState(state.cgroupPath, CgroupV2Frozen); err == nil {
		debugLog("[Complete] Container Frozen Immediately.")
	} else {
		log.Printf("[Complete] Direct Pause Failed (%s): %v", containerName, err)
	}
	
	state.mu.Unlock()

	// -------------------------------------------------------
	// [Step 4] 로그 수집 (비동기)
	// -------------------------------------------------------
	logS3Key := fmt.Sprintf("logs/%s/%s.log", functionId, req.RequestId)
	go s.processAndUploadLogs(containerName, req.RequestId, logSince, logS3Key)

	// -------------------------------------------------------
	// [Step 5] 응답 반환
	// -------------------------------------------------------
	if err != nil {
		// 에러 로그는 중요하므로 항상 출력
		log.Printf("[Req: %s] Runtime Error: %v", req.RequestId, err)
		return &pb.ExecuteResponse{
			Success:      false,
			ErrorMessage: fmt.Sprintf("Runtime Error: %v", err),
			DurationMs:   duration,
			LogS3Key:     logS3Key,
		}, nil
	}

	return &pb.ExecuteResponse{
		Result:       runtimeResp.Result,
		Success:      runtimeResp.Success,
		MemoryUsage:  runtimeResp.MemoryUsage,
		ErrorMessage: runtimeResp.ErrorMessage,
		DurationMs:   duration,
		LogS3Key:     logS3Key,
	}, nil
}

// ==========================================
// 4. 로그 처리 및 S3 업로드 (메모리 최적화)
// ==========================================

func (s *server) processAndUploadLogs(containerName, requestId string, since time.Time, s3Key string) {
	// [핵심 수정] S3 업로드가 꺼져있으면 아예 Docker API 호출도 하지 않고 즉시 종료
	if !EnableS3LogUpload {
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Minute)
	defer cancel()

	// 1. Docker 로그 스트림 열기 (스위치가 켜져있을 때만 실행됨)
	out, err := s.dockerClient.ContainerLogs(ctx, containerName, container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Since:      since.Format(time.RFC3339Nano),
	})
	if err != nil {
		log.Printf("[AsyncLog] Failed to fetch logs: %v", err)
		return
	}
	defer out.Close()

	// [최적화] LimitReader로 최대 읽기 크기 제한 (OOM 방지)
	limitedOut := io.LimitReader(out, MaxLogSize)

	var logBuf bytes.Buffer
	// stdcopy로 Docker 헤더 파싱 (Stdout/Stderr 분리)
	if _, err := stdcopy.StdCopy(&logBuf, &logBuf, limitedOut); err != nil {
		if err != io.EOF {
			debugLog("[AsyncLog] Log copy warning: %v", err)
		}
	}

	// 로그가 잘렸는지 확인
	if int64(logBuf.Len()) >= MaxLogSize {
		logBuf.WriteString("\n...[Logs truncated by Agent due to size limit]...")
	}

	fullLog := logBuf.String()

	// 2. 마커 파싱
	startMarker := fmt.Sprintf("===LOG_START:%s===", requestId)
	endMarker := fmt.Sprintf("===LOG_END:%s===", requestId)
	parsedLog := extractLogContent(fullLog, startMarker, endMarker)

	// 3. S3 업로드
	if s.s3Client != nil && s.logBucket != "" {
		_, err := s.s3Client.PutObject(ctx, &s3.PutObjectInput{
			Bucket:      aws.String(s.logBucket),
			Key:         aws.String(s3Key),
			Body:        strings.NewReader(parsedLog),
			ContentType: aws.String("text/plain"),
		})
		if err != nil {
			log.Printf("[AsyncLog] S3 Upload Failed: %v", err)
		} else {
			debugLog("[AsyncLog] S3 Upload Success: %s", s3Key)
		}
	}
}

func extractLogContent(fullLog, startMarker, endMarker string) string {
	startIndex := strings.Index(fullLog, startMarker)
	if startIndex == -1 {
		return fullLog
	}
	contentStart := startIndex + len(startMarker)
	endIndex := strings.Index(fullLog[contentStart:], endMarker)
	if endIndex == -1 {
		return fullLog[contentStart:]
	}
	return strings.TrimSpace(fullLog[contentStart : contentStart+endIndex])
}

// UDS 통신 (HTTP over Unix Socket)
func callRuntimeViaUDS(socketPath string, payload []byte) (*RuntimeResponse, error) {
	httpClient := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return net.Dial("unix", socketPath)
			},
		},
	}

	req, err := http.NewRequest("POST", "http://unix/invoke", bytes.NewBuffer(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var runtimeResp RuntimeResponse
	if err := json.Unmarshal(body, &runtimeResp); err != nil {
		return nil, fmt.Errorf("response parsing failed: %v", err)
	}
	return &runtimeResp, nil
}

// ==========================================
// 5. Main Entry
// ==========================================

func main() {
	// Docker Client
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		log.Fatalf("Docker client failed: %v", err)
	}
	defer cli.Close()

	// AWS S3 Client
	cfg, err := config.LoadDefaultConfig(context.TODO())
	var s3Client *s3.Client
	if err != nil {
		log.Printf("Warning: Failed to load AWS config: %v", err)
	} else {
		s3Client = s3.NewFromConfig(cfg)
		log.Println("AWS S3 Client initialized.")
	}

	bucketName := os.Getenv("LOG_BUCKET_NAME")
	if bucketName == "" {
		log.Println("Warning: LOG_BUCKET_NAME env is not set.")
	}

	// gRPC Server 설정
	lis, err := net.Listen("tcp", ":9094")
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
	}

	s := grpc.NewServer()
	pb.RegisterAgentServiceServer(s, &server{
		dockerClient: cli,
		s3Client:     s3Client,
		logBucket:    bucketName,
	})

	log.Println("Agent Server listening on :9094...")
	if debugMode {
		log.Println("Debug Mode Enabled (Verbose Logging)")
	}

	if err := s.Serve(lis); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
}