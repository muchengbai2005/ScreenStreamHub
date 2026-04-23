#!/usr/bin/env python3
"""
WebSocket H.264 裸流服务器 + 实时播放
改进版特性：
✅ 实时解码显示画面
✅ 高效解码（使用完整帧数据）
✅ FPS实时显示
✅ 接收统计信息
✅ 支持同时保存文件
✅ 优雅退出
✅ 支持cloudflared内网穿透
✅ 修复SPS/PPS解码问题
"""

import asyncio
import websockets
import os
import time
import argparse
import cv2
import queue
import threading
import numpy as np

# ===================== 全局配置 =====================
connected_clients = set()
output_file = None
file_lock = asyncio.Lock()
args = None

# 帧队列（用于网络线程和解码线程之间的通信）
frame_queue = queue.Queue(maxsize=50)
RUN_FLAG = True
DISPLAY_FLAG = True  # 是否显示画面
display_frame = None
fps_counter = 0
fps_last_time = time.time()
current_fps = 0

# 统计信息
total_frames = 0
total_bytes = 0
start_time = None

# 解码器状态
decoder_initialized = False
sps_data = None
pps_data = None


# ===================== H264帧解析工具 =====================
def find_nal_units(data):
    """查找H264 NAL单元边界"""
    nal_units = []
    i = 0
    while i < len(data):
        # 查找起始码 0x00000001 或 0x000001
        if i + 4 <= len(data) and data[i:i+4] == b'\x00\x00\x00\x01':
            start = i
            i += 4
            # 查找下一个起始码
            next_start = -1
            j = i
            while j < len(data) - 4:
                if data[j:j+4] == b'\x00\x00\x00\x01' or data[j:j+3] == b'\x00\x00\x01':
                    next_start = j
                    break
                j += 1
            if next_start == -1:
                next_start = len(data)
            nal_units.append(data[start:next_start])
            i = next_start
        elif i + 3 <= len(data) and data[i:i+3] == b'\x00\x00\x01':
            start = i
            i += 3
            next_start = -1
            j = i
            while j < len(data) - 4:
                if data[j:j+4] == b'\x00\x00\x00\x01' or data[j:j+3] == b'\x00\x00\x01':
                    next_start = j
                    break
                j += 1
            if next_start == -1:
                next_start = len(data)
            nal_units.append(data[start:next_start])
            i = next_start
        else:
            i += 1
    return nal_units


def get_nal_type(nal_unit):
    """获取NAL单元类型"""
    if len(nal_unit) < 4:
        return -1
    # 检查起始码
    if nal_unit[0:4] == b'\x00\x00\x00\x01':
        return nal_unit[4] & 0x1F
    elif nal_unit[0:3] == b'\x00\x00\x01':
        return nal_unit[3] & 0x1F
    return -1


# ===================== H264解码线程 =====================
def h264_decoder_thread():
    """H264实时解码线程"""
    global display_frame, fps_counter, current_fps, fps_last_time, decoder_initialized
    global sps_data, pps_data
    
    buffer = b""
    min_buffer_size = 50000  # 增加最小缓冲大小
    last_keyframe_time = time.time()
    
    # 创建临时文件用于解码
    temp_file = "___h264_decoder_temp___.h264"
    
    while RUN_FLAG:
        try:
            # 从队列获取数据
            while not frame_queue.empty() and len(buffer) < 200000:  # 限制缓冲区大小
                data = frame_queue.get_nowait()
                buffer += data
                frame_queue.task_done()
            
            # 只有当缓冲区足够大时才尝试解码
            if len(buffer) >= min_buffer_size:
                try:
                    # 查找NAL单元
                    nal_units = find_nal_units(buffer)
                    
                    if nal_units:
                        # 检查是否有SPS/PPS
                        for nal in nal_units:
                            nal_type = get_nal_type(nal)
                            if nal_type == 7:  # SPS
                                sps_data = nal
                                decoder_initialized = True
                                print(f"[S] 找到SPS, 大小: {len(nal)} bytes")
                            elif nal_type == 8:  # PPS
                                pps_data = nal
                                decoder_initialized = True
                                print(f"[P] 找到PPS, 大小: {len(nal)} bytes")
                    
                    # 如果有完整的数据，尝试解码
                    if decoder_initialized and len(nal_units) > 0:
                        # 确保有SPS/PPS开头
                        output_data = b""
                        if sps_data:
                            output_data += sps_data
                        if pps_data:
                            output_data += pps_data
                        
                        # 添加所有NAL单元
                        output_data += b"".join(nal_units)
                        
                        # 写入临时文件
                        with open(temp_file, "wb") as f:
                            f.write(output_data)
                        
                        # 尝试解码
                        cap = cv2.VideoCapture(temp_file)
                        if cap.isOpened():
                            ret, frame = cap.read()
                            if ret and frame is not None:
                                display_frame = frame
                                fps_counter += 1
                                
                                # 更新FPS
                                current_time = time.time()
                                if current_time - fps_last_time >= 1.0:
                                    current_fps = fps_counter
                                    fps_counter = 0
                                    fps_last_time = current_time
                            
                            # 读取所有可用帧
                            while cap.grab():
                                ret, frame = cap.retrieve()
                                if ret and frame is not None:
                                    display_frame = frame
                            
                            cap.release()
                        
                        # 保留最后一个关键帧之后的数据
                        # 找到最后一个关键帧的位置
                        last_keyframe_pos = 0
                        for i, nal in enumerate(nal_units):
                            nal_type = get_nal_type(nal)
                            if nal_type == 5:  # I帧
                                last_keyframe_pos = i
                        
                        if last_keyframe_pos > 0:
                            # 保留关键帧及其后面的数据
                            buffer = b"".join(nal_units[last_keyframe_pos:])
                        else:
                            # 没有找到关键帧，保留最后一部分
                            buffer = buffer[-10000:] if len(buffer) > 10000 else b""
                    else:
                        # 解码器未初始化，清空缓冲（等待SPS/PPS）
                        if time.time() - last_keyframe_time > 5:
                            buffer = b""
                            print("[!] 等待SPS/PPS...")
                    
                except Exception as e:
                    print(f"[!] 解码错误: {str(e)[:50]}")
                    # 解码失败，保留部分数据
                    buffer = buffer[-10000:] if len(buffer) > 10000 else b""
            
            time.sleep(0.005)  # 减少CPU占用
        
        except Exception as e:
            print(f"[!] 解码器线程错误: {str(e)[:50]}")
            time.sleep(0.1)
    
    # 清理
    if os.path.exists(temp_file):
        try:
            os.remove(temp_file)
        except:
            pass


# ===================== CV窗口显示线程 =====================
def cv_window_loop():
    """OpenCV窗口显示主线程"""
    global RUN_FLAG, DISPLAY_FLAG, display_frame, current_fps, total_frames, total_bytes
    
    cv2.namedWindow("H264 Live Stream", cv2.WINDOW_NORMAL)
    cv2.resizeWindow("H264 Live Stream", 800, 600)
    
    while RUN_FLAG:
        if DISPLAY_FLAG and display_frame is not None:
            # 在画面上叠加信息
            frame_with_info = display_frame.copy()
            
            # 计算统计信息
            elapsed_time = time.time() - start_time if start_time else 0
            bitrate = (total_bytes * 8 / 1024 / 1024) / max(elapsed_time, 1) if elapsed_time > 0 else 0
            
            # 添加文字信息
            info_lines = [
                f"FPS: {current_fps}",
                f"Frames: {total_frames}",
                f"Size: {total_bytes / 1024 / 1024:.2f} MB",
                f"Bitrate: {bitrate:.2f} Mbps",
                "Press 'q' to quit | 'f' to toggle fullscreen"
            ]
            
            y_offset = 30
            for line in info_lines:
                cv2.putText(frame_with_info, line, (10, y_offset), 
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)
                y_offset += 20
            
            cv2.imshow("H264 Live Stream", frame_with_info)
        elif not DISPLAY_FLAG:
            # 显示等待画面
            wait_frame = np.zeros((480, 640, 3), dtype=np.uint8)
            cv2.putText(wait_frame, "Waiting for stream...", (100, 240), 
                        cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
            cv2.imshow("H264 Live Stream", wait_frame)
        
        # 这一行保证窗口永远不卡死
        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'):
            RUN_FLAG = False
            break
        elif key == ord('f'):
            # 切换全屏
            current_state = cv2.getWindowProperty("H264 Live Stream", cv2.WND_PROP_FULLSCREEN)
            cv2.setWindowProperty("H264 Live Stream", cv2.WND_PROP_FULLSCREEN, 
                                cv2.WINDOW_FULLSCREEN if current_state == 0 else cv2.WINDOW_NORMAL)
        elif key == ord('p'):
            # 暂停/继续显示
            DISPLAY_FLAG = not DISPLAY_FLAG
            print(f"[*] 显示: {'开启' if DISPLAY_FLAG else '暂停'}")
    
    cv2.destroyAllWindows()
    print("\n[+] 窗口已关闭")


# ===================== WebSocket 服务 =====================
async def handle_client(websocket):
    """处理客户端连接"""
    global output_file, total_frames, total_bytes, start_time, decoder_initialized
    global DISPLAY_FLAG
    
    path = websocket.request.path if hasattr(websocket, 'request') else '/'
    print(f"\n[+] 客户端已连接: {websocket.remote_address}")
    print(f"[*] 连接路径: {path}")
    connected_clients.add(websocket)
    print(f"[*] 当前在线客户端: {len(connected_clients)}")
    
    # 记录开始时间
    if start_time is None:
        start_time = time.time()
    
    # 重置解码器状态
    decoder_initialized = False
    
    try:
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        filename = f"h264_stream_{timestamp}.h264"
        
        async with file_lock:
            if output_file is not None:
                output_file.close()
            output_file = open(filename, 'wb')
            print(f"[+] 开始写入文件: {filename}")
        
        frame_count = 0
        
        async for message in websocket:
            if isinstance(message, bytes):
                frame_size = len(message)
                frame_count += 1
                total_frames += 1
                total_bytes += frame_size
                
                # 发送到解码队列
                try:
                    frame_queue.put_nowait(message)
                except queue.Full:
                    print(f"[!] 队列已满，丢弃帧")
                
                # 写入文件
                async with file_lock:
                    if output_file:
                        output_file.write(message)
                        output_file.flush()
                
                # 检测帧类型
                if len(message) > 4:
                    nal_type = -1
                    if message[0:4] == b'\x00\x00\x00\x01':
                        nal_type = message[4] & 0x1F
                    elif message[0:3] == b'\x00\x00\x01':
                        nal_type = message[3] & 0x1F
                    
                    if nal_type == 5:
                        print(f"[I] 关键帧, 帧: {frame_count}, 大小: {frame_size} bytes")
                    elif nal_type == 7:
                        print(f"[S] SPS 帧, 帧: {frame_count}, 大小: {frame_size} bytes")
                    elif nal_type == 8:
                        print(f"[P] PPS 帧, 帧: {frame_count}, 大小: {frame_size} bytes")
                
                # 定期输出统计
                if frame_count % 100 == 0:
                    elapsed = time.time() - start_time if start_time else 1
                    avg_fps = frame_count / elapsed
                    print(f"[*] 已接收 {frame_count} 帧, 总大小: {total_bytes / 1024 / 1024:.2f} MB, 平均FPS: {avg_fps:.1f}")
            
            elif isinstance(message, str):
                print(f"[T] 收到文本消息: {message}")
    
    except websockets.exceptions.ConnectionClosed:
        print(f"[-] 客户端断开连接: {websocket.remote_address}")
    except Exception as e:
        print(f"[!] 发生错误: {e}")
    finally:
        connected_clients.discard(websocket)
        print(f"[*] 当前在线客户端: {len(connected_clients)}")
        
        async with file_lock:
            if output_file:
                output_file.close()
                output_file = None
                print("[+] 文件已关闭")
        
        # 重置状态
        if len(connected_clients) == 0:
            # 没有客户端了，重置统计
            total_frames = 0
            total_bytes = 0
            start_time = None
            decoder_initialized = False
            # 清空帧队列
            while not frame_queue.empty():
                try:
                    frame_queue.get_nowait()
                except:
                    pass
            print("[*] 所有客户端已断开，等待新连接...")


async def main():
    """启动 WebSocket 服务器"""
    server = await websockets.serve(
        handle_client,
        '0.0.0.0',
        args.port,
        max_size=10 * 1024 * 1024
    )
    
    print("=" * 60)
    print("✅ H264 实时流服务器启动成功")
    print("=" * 60)
    print(f"本地地址: ws://localhost:{args.port}/stream")
    print(f"网络地址: ws://0.0.0.0:{args.port}/stream")
    print("=" * 60)
    print("使用 cloudflared 内网穿透:")
    print(f"  cloudflared.exe tunnel --url http://localhost:{args.port}")
    print("=" * 60)
    print("快捷键:")
    print("  q - 退出程序")
    print("  f - 切换全屏")
    print("  p - 暂停/继续显示")
    print("=" * 60)
    print("等待客户端连接...")
    print("-" * 60)
    
    # 启动H264解码线程
    threading.Thread(target=h264_decoder_thread, daemon=True).start()
    
    async with server:
        await server.wait_closed()


# ===================== 启动 =====================
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WebSocket H.264 裸流实时服务器")
    parser.add_argument("--port", type=int, default=8765, help="监听端口")
    args = parser.parse_args()
    
    # 创建输出目录
    if not os.path.exists("output"):
        os.makedirs("output")
    os.chdir("output")
    
    try:
        # 启动WebSocket服务（在后台线程）
        threading.Thread(target=lambda: asyncio.run(main()), daemon=True).start()
        
        # 主线程运行CV窗口
        cv_window_loop()
        
    except KeyboardInterrupt:
        print("\n[+] 服务器已停止")
    finally:
        RUN_FLAG = False
        if output_file:
            output_file.close()