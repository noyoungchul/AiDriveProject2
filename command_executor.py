#!/usr/bin/env python3
import rclpy
from rclpy.node import Node
from std_msgs.msg import String, Bool
from geometry_msgs.msg import Twist
import subprocess

class CommandExecutor(Node):
    def __init__(self):
        super().__init__('command_executor')

        # ---- 구독 & 퍼블리셔 ----
        self.create_subscription(String, '/cmd_drive', self.cmd_drive_callback, 10)
        self.cmd_vel_pub = self.create_publisher(Twist, '/cmd_vel', 10)
        self.speed_pub = self.create_publisher(String, '/speed_cmd', 10)
        self.emergency_pub = self.create_publisher(Bool, '/emergency_stop', 10)

        # ---- 상태 ----
        self.process = None
        self.is_running = False

        self.get_logger().info("✅ CommandExecutor 노드 초기화 완료")

    def cmd_drive_callback(self, msg: String):
        cmd = msg.data.strip().lower()
        self.get_logger().info(f"📩 수신 명령: {cmd}")

        if cmd == "start" and not self.is_running:
            self.get_logger().info("🚀 motor_sub 실행 중...")
            self.emergency_pub.publish(Bool(data=False))  # ✅ 긴급정지 해제 신호
            try:
                self.process = subprocess.Popen(["ros2", "run", "bot_pkg", "motor_sub"])
                self.is_running = True
            except Exception as e:
                self.get_logger().error(f"motor_sub 실행 실패: {e}")

        elif cmd == "stop":
            self.get_logger().info("🛑 STOP 명령 감지 — 정지 시도")
            self.cmd_vel_pub.publish(Twist())  # 정지 명령 퍼블리시
            self.emergency_pub.publish(Bool(data=False))  # 긴급정지 아님
            self.speed_pub.publish(String(data="stop"))   # 속도 초기화 명령
            self.safe_stop()
            self.is_running = False

        elif cmd == "emergency":
            self.get_logger().warn("⚠️ Emergency Stop 수신!")
            self.emergency_pub.publish(Bool(data=True))
            self.safe_stop()

        elif cmd in ["up", "down"]:
            self.speed_pub.publish(String(data=cmd))

    def safe_stop(self):
        # motor_sub 종료
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=2)
                self.get_logger().info("✅ motor_sub 정상 종료")
            except Exception:
                self.process.kill()
            finally:
                self.process = None

        # cmd_vel 정지
        twist = Twist()
        twist.linear.x = 0.0
        twist.angular.z = 0.0
        self.cmd_vel_pub.publish(twist)
        self.get_logger().info("✅ /cmd_vel 정지 명령 퍼블리시 완료")

def main(args=None):
    rclpy.init(args=args)
    node = CommandExecutor()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.safe_stop()
        node.destroy_node()
        rclpy.shutdown()

if __name__ == '__main__':
    main()
