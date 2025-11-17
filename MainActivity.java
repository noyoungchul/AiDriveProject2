package com.cookandroid.aidriveexample; // 이 자바 파일의 패키지 선언

// --- 안드로이드/뷰 관련 임포트 ---
import android.os.Bundle; // 액티비티 생명주기 관리에 사용
import android.os.Handler; // UI 스레드에 작업을 예약할 때 사용
import android.util.Log; // 로그 출력용
import android.view.MotionEvent; // 터치 이벤트 정보를 담는 클래스
import android.view.View; // 뷰 기본 타입
import android.widget.Button; // 버튼 위젯
import android.widget.ImageButton;
import android.widget.ImageView; // 이미지 표시 위젯
import android.widget.LinearLayout; //리니어 레이아웃
import android.widget.ProgressBar; // 프로그레스바(배터리 표시 등)
import android.widget.TextView; // 텍스트 표시 위젯
import android.widget.Toast; // 간단한 팝업 알림(토스트)

// androidx 앱 호환성 액티비티
import androidx.appcompat.app.AppCompatActivity; // AppCompat 기반 액티비티

// --- 이미지/디코드 관련 임포트 ---
import android.util.Base64; // Base64 디코딩
import android.graphics.Bitmap; // 비트맵 이미지 객체
import android.graphics.BitmapFactory; // 바이트배열 -> 비트맵 디코더

// --- JSON 관련 임포트 ---
import org.json.JSONException; // JSON 처리 중 발생하는 예외
import org.json.JSONObject; // JSON 객체 생성/파싱

import java.util.Locale; // Locale 지정하여 문자열 포맷

// --- OkHttp (WebSocket 통신용) 임포트 ---
import okhttp3.OkHttpClient; // OkHttp 클라이언트
import okhttp3.Request; // HTTP/WS 요청 빌더
import okhttp3.Response; // 응답 객체 (콜백에서 사용)
import okhttp3.WebSocket; // WebSocket 인터페이스
import okhttp3.WebSocketListener; // WebSocket 이벤트 리스너

// ============================================================
// MainActivity: ROS2 rosbridge와 WebSocket 통신 + UI 제어
//  - 배터리, 오돔, IMU, 카메라 구독
//  - cmd_vel 퍼블리시 및 긴급정지, 목표 지정 등
// ============================================================
public class MainActivity extends AppCompatActivity { // AppCompatActivity를 상속한 액티비티 클래스

    private static final String TAG = "MainActivity"; // 로그 태그로 사용할 상수

    // --- UI 컴포넌트 선언 ---
    private ProgressBar batteryProgress; // 배터리 진행 표시 바
    private TextView tvBattery, tvNetwork, tvStatus, tvOdom, tvImu; // 텍스트뷰들: 배터리, 네트워크, 상태, 오돔, IMU
    private Button btnEmergency; // 제어용 버튼들
    private LinearLayout layoutStartButton, layoutStopButton;
    private LinearLayout layoutSpeedDownButton, layoutSpeedUpButton;
    private ImageButton btnLeft, btnRight;
    private ImageView ivCamera; // 카메라 영상 표시용 이미지뷰

    // --- 네트워크/rosbridge 필드 ---
    private OkHttpClient client; // OkHttp 클라이언트 인스턴스
    private WebSocket webSocket; // 연결된 WebSocket 인스턴스
    private final Handler uiHandler = new Handler(); // UI 스레드로 실행할 때 사용할 핸들러

    // rosbridge 주소: 실제 환경에 맞게 변경 필요
    private final String ROSBRIDGE_URI = "ws://192.168.0.3:9090"; // rosbridge websocket URI


    // 구독 식별자(나중에 unsubscribe할 때 사용)
    private final String SUB_BATTERY = "sub_battery"; // 배터리 구독 id
    private final String SUB_ODOM = "sub_odom"; // 오돔 구독 id
    private final String SUB_IMU = "sub_imu"; // IMU 구독 id
    private final String SUB_CAMERA = "sub_camera"; // 카메라 구독 id

    private boolean manualMode = false; // 수동 조종 모드 여부 플래그

    // ----------------------------------------------------------
    // 액티비티가 생성될 때 호출되는 콜백 (생명주기)
    // ----------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) { // 액티비티 시작 시 초기화
        super.onCreate(savedInstanceState); // 부모 클래스의 onCreate 호출
        setContentView(R.layout.activity_main); // 레이아웃 resource(activity_main.xml) 연결

        bindViews();       // 레이아웃의 뷰들을 멤버 변수에 연결
        setupButtons();    // 버튼들의 리스너 설정

        initWebSocket();   // rosbridge(WebSocket) 초기화 및 연결 시도
    }

    // ----------------------------------------------------------
    // 레이아웃 뷰들을 찾아 멤버변수에 할당
    // ----------------------------------------------------------
    private void bindViews() {
        batteryProgress = findViewById(R.id.batteryProgress); // 프로그레스바 연결
        tvBattery = findViewById(R.id.tvBattery); // 배터리 텍스트뷰 연결
        tvNetwork = findViewById(R.id.tvNetwork); // 네트워크 텍스트뷰 연결
        tvStatus = findViewById(R.id.tvStatus); // 상태 텍스트뷰 연결
        tvOdom = findViewById(R.id.tvOdom); // 오돔 텍스트뷰 연결
        tvImu = findViewById(R.id.tvImu); // IMU 텍스트뷰 연결
        btnEmergency = findViewById(R.id.btnEmergency); // 긴급정지 버튼 연결
        ivCamera = findViewById(R.id.ivCameraPlaceholder); // 카메라 이미지뷰 연결 (XML id 확인)


        layoutStartButton = findViewById(R.id.layoutStartButton); //주행시작 버튼
        layoutStopButton = findViewById(R.id.layoutStopButton); //정지 버튼
        layoutSpeedDownButton = findViewById(R.id.layoutSpeedDownButton); //감속 버튼
        layoutSpeedUpButton = findViewById(R.id.layoutSpeedUpButton); //가속 버튼


        btnLeft = findViewById(R.id.btnLeft); //왼쪽 방향키
        btnRight = findViewById(R.id.btnRight); //오른쪽 방향키
    }

    // ----------------------------------------------------------
    // 버튼 클릭 리스너 설정
    // ----------------------------------------------------------
    private void setupButtons() {

        // ✅ 커스텀 시작 버튼 리스너 (LinearLayout에 연결)
        layoutStartButton.setOnClickListener(v -> {
            publishDriveCommand("start");
            tvStatus.setText("상태: 주행 중");
            Toast.makeText(this, "주행 시작", Toast.LENGTH_SHORT).show();
        });

        // ✅ 커스텀 정지 버튼 리스너 (LinearLayout에 연결)
        layoutStopButton.setOnClickListener(v -> {
            publishDriveCommand("stop");
            tvStatus.setText("상태: 정지");
            Toast.makeText(this, "주행 정지", Toast.LENGTH_SHORT).show();
        });

        // ✅ 감속 버튼 리스너 (LinearLayout에 연결)
        layoutSpeedDownButton.setOnClickListener(v -> {
            publishDriveCommand("down"); // motor_sub 속도 감소
            Toast.makeText(this, "감속", Toast.LENGTH_SHORT).show();
            // TODO: 감속 로직 추가
        });

        // ✅ 가속 버튼 리스너 (LinearLayout에 연결)
        layoutSpeedUpButton.setOnClickListener(v -> {
            publishDriveCommand("up"); // motor_sub 속도 증가
            Toast.makeText(this, "가속", Toast.LENGTH_SHORT).show();
            // TODO: 가속 로직 추가
        });
        btnEmergency.setOnClickListener(v -> publishEmergency()); // 긴급정지 버튼: emergency 메시지 전송

        // ✅ 방향키 버튼 설정
        setupDirectionalButtons();
    }

    private void setupDirectionalButtons() {

        // 좌회전 버튼
        btnLeft.setOnClickListener(v -> {
            publishLaneChange("left"); // ros로 좌회전 명령 전송
            Toast.makeText(this, "좌회전", Toast.LENGTH_SHORT).show();
        });

        // 우회전 버튼
        btnRight.setOnClickListener(v -> {
            publishLaneChange("right"); // ros로 우회전 명령 전송
            Toast.makeText(this, "우회전", Toast.LENGTH_SHORT).show();
        });
    }
    private void publishLaneChange(String direction) {
        if (webSocket == null) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("op", "publish");
            msg.put("topic", "/lane_change_cmd");

            JSONObject data = new JSONObject();
            data.put("data", direction);
            msg.put("msg", data);

            webSocket.send(msg.toString());
        } catch (JSONException e) { e.printStackTrace(); }
    }
    private void publishDriveCommand(String command) {
        if (webSocket == null) {
            Toast.makeText(this, "ROS 연결 안 됨", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("op", "publish");               // 퍼블리시 동작
            msg.put("topic", "/cmd_drive");         // command_executor에서 구독 중인 토픽

            JSONObject data = new JSONObject();
            data.put("data", command);              // 메시지 내용: "start" 또는 "stop"
            msg.put("msg", data);

            webSocket.send(msg.toString());
            Log.i("MainActivity", "Sent /cmd_drive: " + command);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // ----------------------------------------------------------
    // WebSocket 초기화 및 rosbridge 연결 설정
    // ----------------------------------------------------------
    private void initWebSocket() {
        client = new OkHttpClient(); // OkHttp 클라이언트 인스턴스 생성
        Request request = new Request.Builder().url(ROSBRIDGE_URI).build(); // WebSocket 요청 생성

        webSocket = client.newWebSocket(request, new WebSocketListener() { // 비동기 WebSocket 연결 시도
            @Override public void onOpen(WebSocket webSocket, Response response) { // 연결 성공 콜백
                Log.i(TAG, "WebSocket opened"); // 로그 출력

                // UI 변경은 메인(UI) 스레드에서 수행
                uiHandler.post(() -> {
                    tvNetwork.setText("네트워크: 연결됨"); // 네트워크 상태 텍스트 갱신
                    layoutStartButton.setEnabled(true); // 연결되면 버튼 활성화
                    layoutStopButton.setEnabled(true);
                });

                // 연결되면 필요한 토픽들 구독 요청 전송
                subscribeTopic("/battery_state", "sensor_msgs/msg/BatteryState", SUB_BATTERY); // 배터리 구독
                subscribeTopic("/odom", "nav_msgs/msg/Odometry", SUB_ODOM); // 오돔 구독
                subscribeTopic("/imu", "sensor_msgs/msg/Imu", SUB_IMU); // IMU 구독 (타입 정확히 sensor_msgs/msg/Imu)
                subscribeTopic("/lane_image_raw", "sensor_msgs/msg/Image", SUB_CAMERA); // 카메라 압축 이미지 구독
            }

            @Override public void onMessage(WebSocket webSocket, String text) { // 메시지 수신 콜백
                handleRosbridgeMessage(text); // 수신된 JSON 메시지 처리
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) { // 연결 실패 콜백
                Log.e(TAG, "WebSocket failure", t); // 오류 로그 남김
                uiHandler.post(() -> {
                    tvNetwork.setText("네트워크: 연결실패"); // UI에 실패 표시
                    uiHandler.postDelayed(() -> initWebSocket(), 5000); // 5초 후 재시도
                });
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) { // 연결 종료 콜백
                uiHandler.post(() -> tvNetwork.setText("네트워크: 닫힘")); // UI에 닫힘 표시
            }
        });

        // Note: client.dispatcher().executorService().shutdown()는 onDestroy에서 호출
    }

    // ----------------------------------------------------------
    // rosbridge에 subscribe 요청 전송 (JSON)
    // ----------------------------------------------------------
    private void subscribeTopic(String topic, String type, String id) {
        try {
            JSONObject obj = new JSONObject(); // JSON 객체 생성
            obj.put("op", "subscribe"); // rosbridge op: subscribe
            obj.put("topic", topic); // 구독할 토픽 이름
            obj.put("type", type); // 메시지 타입 문자열 (ROS2 형식)
            obj.put("id", id); // 구독 식별자
            webSocket.send(obj.toString()); // JSON 문자열을 WebSocket으로 전송
        } catch (JSONException e) { // JSON 빌드 실패 시
            e.printStackTrace(); // 예외 스택트레이스 출력
        }
    }

    // ----------------------------------------------------------
    // rosbridge에 unsubscribe 요청 전송
    // ----------------------------------------------------------
    private void unsubscribeTopic(String id) {
        try {
            JSONObject obj = new JSONObject(); // JSON 객체 생성
            obj.put("op", "unsubscribe"); // rosbridge op: unsubscribe
            obj.put("id", id); // 해제할 구독 id
            webSocket.send(obj.toString()); // 전송
        } catch (JSONException e) {
            e.printStackTrace(); // 예외 처리
        }
    }

    // ----------------------------------------------------------
    // rosbridge에서 받은 메시지(JSON 문자열) 파싱 및 처리
    // ----------------------------------------------------------
    private void handleRosbridgeMessage(String text) {
        try {
            JSONObject obj = new JSONObject(text); // 수신 문자열을 JSON으로 파싱
            if (!obj.has("topic")) return; // topic 필드가 없으면 무시

            String topic = obj.getString("topic"); // 토픽 이름 추출
            JSONObject msg = obj.getJSONObject("msg"); // msg 페이로드 추출


            if (topic.equals("/battery_state")) { // 배터리 상태 처리
                double percentage = 0.0;
                if (msg.has("percentage")) {
                    percentage = msg.getDouble("percentage");
                    // 이미 0~100 범위라면 100 이상이면 1.0으로 보정
                    if (percentage > 1.0) {
                        percentage = percentage / 100.0;
                    }
                } else if (msg.has("voltage")) {
                    double v = msg.getDouble("voltage");
                    percentage = v / 12.6; // 12.6V = 100%
                }

                // 안전하게 0~1 범위로 클램프
                percentage = Math.max(0.0, Math.min(1.0, percentage));

                final int pct = (int) Math.round(percentage * 100.0);
                uiHandler.post(() -> updateBatteryUI(pct)); // UI 업데이트는 UI 스레드에서 수행

            } else if (topic.equals("/odom")) { // 오돔(속도/거리) 처리
                JSONObject twist = msg.getJSONObject("twist").getJSONObject("twist"); // twist.twist 접근
                JSONObject linear = twist.getJSONObject("linear"); // linear 필드
                double vx = linear.optDouble("x", 0.0); // 선형 속도 x
                JSONObject pose = msg.getJSONObject("pose").getJSONObject("pose"); // pose.pose 접근
                double px = pose.getJSONObject("position").optDouble("x", 0.0); // 위치 x
                uiHandler.post(() -> tvOdom.setText(String.format(Locale.US, "속도: %.2f m/s  거리: %.2f m", vx, px))); // UI에 표시

            } else if (topic.equals("/imu")) { // IMU 처리: orientation(쿼터니언) -> 오일러 각으로 변환
                JSONObject orientation = msg.getJSONObject("orientation"); // orientation 객체
                double x = orientation.optDouble("x", 0.0); // 쿼터니언 x
                double y = orientation.optDouble("y", 0.0); // 쿼터니언 y
                double z = orientation.optDouble("z", 0.0); // 쿼터니언 z
                double w = orientation.optDouble("w", 1.0); // 쿼터니언 w

                // 쿼터니언 -> 오일러(롤, 피치, 요) 변환 수식
                double sinr_cosp = 2 * (w * x + y * z);
                double cosr_cosp = 1 - 2 * (x * x + y * y);
                double roll = Math.toDegrees(Math.atan2(sinr_cosp, cosr_cosp)); // roll(도)

                double sinp = 2 * (w * y - z * x);
                double pitch;
                if (Math.abs(sinp) >= 1) // asin 범위 초과 방지(클램핑)
                    pitch = Math.toDegrees(Math.copySign(Math.PI / 2, sinp));
                else
                    pitch = Math.toDegrees(Math.asin(sinp));

                double siny_cosp = 2 * (w * z + x * y);
                double cosy_cosp = 1 - 2 * (y * y + z * z);
                double yaw = Math.toDegrees(Math.atan2(siny_cosp, cosy_cosp)); // yaw(도)

                uiHandler.post(() -> tvImu.setText(String.format("IMU: R%.1f° P%.1f° Y%.1f°", roll, pitch, yaw))); // UI에 표시

            } else if (topic.equals("/lane_image_raw")) {
                if (msg.has("data") && msg.has("encoding") && msg.has("height") && msg.has("width")) {
                    String dataBase64 = msg.getString("data");
                    byte[] data = Base64.decode(dataBase64, Base64.DEFAULT);

                    int width = msg.getInt("width");
                    int height = msg.getInt("height");

                    String encoding = msg.getString("encoding");
                    if (encoding.equals("bgr8") || encoding.equals("rgb8")) {
                        int[] pixels = new int[width * height];
                        for (int i = 0; i < width * height; i++) {
                            int r = data[i * 3] & 0xFF;
                            int g = data[i * 3 + 1] & 0xFF;
                            int b = data[i * 3 + 2] & 0xFF;
                            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                        Bitmap bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                        uiHandler.post(() -> ivCamera.setImageBitmap(bmp));
                    }
                }
            }

        } catch (JSONException e) {
            Log.w(TAG, "Failed parse rosbridge msg", e);
        }
    }

    // ----------------------------------------------------------
    // 배터리 UI 업데이트 및 안전 처리
    // ----------------------------------------------------------
    private void updateBatteryUI(int pct) {
        batteryProgress.setProgress(pct); // 프로그레스바 업데이트
        tvBattery.setText(pct + "%"); // 퍼센트 텍스트 갱신
        if (pct <= 20) { // 임계값 이하일 때 안전조치
            tvBattery.setTextColor(0xFFFF0000); // 빨간색으로 표시
            publishCmdVel(0.0, 0.0, false); // 즉시 정지 명령 전송
            layoutStartButton.setEnabled(false); // 시작 버튼 비활성화
            Toast.makeText(this, "배터리 부족: 주행 불가", Toast.LENGTH_LONG).show(); // 사용자 알림
            tvStatus.setText("상태: 배터리 낮음"); // 상태 텍스트 갱신
        } else {
            tvBattery.setTextColor(0xFF000000); // 정상 시 검정색으로
        }
    }

    // ----------------------------------------------------------
    // cmd_vel 퍼블리시 (geometry_msgs/Twist 형식으로 전송)
    // ----------------------------------------------------------
    private void publishCmdVel(double linear, double angular, boolean showStatus) {
        if (webSocket == null) return; // 웹소켓 미연결 시 무시
        try {
            JSONObject msg = new JSONObject(); // publish 요청 전체
            JSONObject linearObj = new JSONObject(); // twist.linear 객체
            linearObj.put("x", linear); // 선형 x 설정
            linearObj.put("y", 0.0); // 선형 y 0
            linearObj.put("z", 0.0); // 선형 z 0

            JSONObject angularObj = new JSONObject(); // twist.angular 객체
            angularObj.put("x", 0.0); // 각속도 x 0
            angularObj.put("y", 0.0); // 각속도 y 0
            angularObj.put("z", angular); // 각속도 z (yaw)

            JSONObject twist = new JSONObject(); // twist 객체
            twist.put("linear", linearObj); // twist.linear = ...
            twist.put("angular", angularObj); // twist.angular = ...

            msg.put("op", "publish"); // rosbridge op: publish
            msg.put("topic", "/cmd_vel"); // 퍼블리시할 토픽명
            msg.put("msg", twist); // msg에 twist 객체 삽입

            webSocket.send(msg.toString()); // JSON 문자열을 WebSocket으로 전송
            if (showStatus) // showStatus가 true이면 UI의 상태 텍스트를 갱신
                uiHandler.post(() -> tvStatus.setText(linear > 0 ? "상태: 주행 중" : "상태: 정지"));

        } catch (JSONException e) { // JSON 빌드 실패 시
            e.printStackTrace(); // 예외 출력
        }
    }

    // ----------------------------------------------------------
    // 긴급 정지 퍼블리시 (std_msgs/Bool 형태)
    // ----------------------------------------------------------
    private void publishEmergency() {
        if (webSocket == null) return; // 웹소켓 미연결 시 무시
        try {
            // 1️⃣ std_msgs/Bool 메시지 생성
            JSONObject boolMsg = new JSONObject();
            boolMsg.put("data", true); // true = 긴급정지 신호

            // 2️⃣ rosbridge publish 요청 JSON
            JSONObject obj = new JSONObject();
            obj.put("op", "publish");
            obj.put("topic", "/emergency_stop"); // ROS 노드에서 구독하는 토픽
            obj.put("msg", boolMsg);

            // 3️⃣ WebSocket으로 전송
            webSocket.send(obj.toString());

            // 4️⃣ UI 업데이트 (토스트 + 상태 표시)
            uiHandler.post(() -> {
                tvStatus.setText("상태: 긴급 정지!!"); // 상태 텍스트 갱신
                Toast.makeText(this, "긴급 정지 발동", Toast.LENGTH_SHORT).show();
            });

        } catch (JSONException e) {
            e.printStackTrace(); // 예외 출력
        }
    }

    // ----------------------------------------------------------
// 목표 좌표 전송 (geometry_msgs/PoseStamped 형식)
// ----------------------------------------------------------
    private void publishPoseStamped(double x, double y, double yaw) {
        if (webSocket == null) return; // 웹소켓 미연결 시 무시
        try {
            JSONObject position = new JSONObject(); // position 객체 생성
            position.put("x", x); // x 좌표 설정
            position.put("y", y); // y 좌표 설정
            position.put("z", 0.0); // z 좌표 기본값

            // yaw(라디안)을 쿼터니언으로 변환
            double halfYaw = yaw / 2.0;
            double qw = Math.cos(halfYaw);
            double qz = Math.sin(halfYaw);

            JSONObject orientation = new JSONObject();
            orientation.put("x", 0.0);
            orientation.put("y", 0.0);
            orientation.put("z", qz);
            orientation.put("w", qw);

            JSONObject pose = new JSONObject();
            pose.put("position", position);
            pose.put("orientation", orientation);

            JSONObject header = new JSONObject();
            header.put("frame_id", "map"); // 좌표 기준 프레임

            JSONObject msg = new JSONObject();
            msg.put("header", header);
            msg.put("pose", pose);

            JSONObject obj = new JSONObject();
            obj.put("op", "publish");
            obj.put("topic", "/goal_pose");
            obj.put("msg", msg);

            webSocket.send(obj.toString());
            uiHandler.post(() -> tvStatus.setText("상태: 목표 전송 완료"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() { // 액티비티가 종료될 때 호출
        super.onDestroy(); // 부모 onDestroy 호출
        if (webSocket != null) { // 웹소켓이 존재하면
            unsubscribeTopic(SUB_BATTERY); // 배터리 구독 해제
            unsubscribeTopic(SUB_ODOM); // 오돔 구독 해제
            unsubscribeTopic(SUB_IMU); // IMU 구독 해제
            unsubscribeTopic(SUB_CAMERA); // 카메라 구독 해제
            webSocket.close(1000, "Activity destroyed"); // 정상 코드로 소켓 닫기
        }
        if (client != null) { // OkHttp 클라이언트 정리
            client.dispatcher().executorService().shutdown(); // 스레드풀 종료
        }
    }
}
