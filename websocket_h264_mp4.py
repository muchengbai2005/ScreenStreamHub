#!/usr/bin/env python3
"""
WebSocket H.264 裸流服务器 - 直接录制为MP4格式
特性：
✅ 直接录制为MP4格式，包含时间戳信息
✅ 播放流畅，不会有"减速"效果
✅ 支持实时查看统计信息
✅ 支持cloudflared内网穿透
"""

import asyncio
import websockets
import os
import time
import argparse
import subprocess
import threading
import queue

# ===================== 全局配置 =====================
connected_clients = set()
args = None

# 帧队列
frame_queue = queue.Queue(maxsize=50)
RUN_FLAG = True

# 统计信息
total_frames = 0
total_bytes = 0
start_time = None


# ===================== FFmpeg编码线程 =====================
def ffmpeg_encoder_thread(output_filename):
    """使用FFmpeg将H264裸流编码为MP4"""
    global total_frames, total_bytes
    
    # FFmpeg命令：接收stdin的H264裸流，输出MP4
    cmd = [
        'ffmpeg',
        '-f', 'h264',           # 输入格式为H264裸流
        '-i', '-',               # 从stdin读取
        '-c:v', 'copy',          # 直接复制视频流（不重新编码）
        '-movflags', 'faststart', # 将moov原子移到文件开头（便于流式播放）
        '-y',                    # 覆盖输出文件
        output_filename
    ]
    
    try:
        process = subprocess.Popen(cmd, stdin=subprocess.PIPE, 
                                   stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
        
        while RUN_FLAG:
            try:
                # 从队列获取数据
                data = frame_queue.get(timeout=0.1)
                total_bytes += len(data)
                total_frames += 1
                
                # 写入FFmpeg进程
                process.stdin.write(data)
                process.stdin.flush()
                
            except queue.Empty:
                continue
            except BrokenPipeError:
                print("[!] FFmpeg进程已断开")
                break
            except Exception as e:
                print(f"[!] 编码错误: {e}")
                break
        
        # 关闭进程
        if process.stdin:
            try:
                process.stdin.close()
            except:
                pass
        process.wait(timeout=5)
        print(f"[+] MP4文件已生成: {output_filename}")
        
    except Exception as e:
        print(f"[!] 无法启动FFmpeg: {e}")
        print("请确保FFmpeg已安装并添加到PATH环境变量")


# ===================== 统计信息线程 =====================
def stats_thread():
    """定期输出统计信息"""
    global total_frames, total_bytes, start_time
    
    while RUN_FLAG:
        if start_time and total_frames > 0:
            elapsed = time.time() - start_time
            avg_fps = total_frames / elapsed
            bitrate = (total_bytes * 8 / 1024 / 1024) / elapsed
            print(f"[*] 帧: {total_frames}, 大小: {total_bytes/1024/1024:.2f} MB, FPS: {avg_fps:.1f}, 码率: {bitrate:.2f} Mbps")
        
        time.sleep(2)


# ===================== WebSocket 服务 =====================
async def handle_client(websocket):
    """处理客户端连接"""
    global start_time
    
    path = websocket.request.path if hasattr(websocket, 'request') else '/'
    print(f"\n[+] 客户端已连接: {websocket.remote_address}")
    print(f"[*] 连接路径: {path}")
    connected_clients.add(websocket)
    print(f"[*] 当前在线客户端: {len(connected_clients)}")
    
    # 记录开始时间
    if start_time is None:
        start_time = time.time()
    
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    output_filename = f"h264_stream_{timestamp}.mp4"
    
    # 启动FFmpeg编码线程
    encoder_thread = threading.Thread(target=ffmpeg_encoder_thread, 
                                      args=(output_filename,), 
                                      daemon=True)
    encoder_thread.start()
    
    try:
        frame_count = 0
        
        async for message in websocket:
            if isinstance(message, bytes):
                frame_size = len(message)
                frame_count += 1
                
                # 发送到编码队列
                try:
                    frame_queue.put_nowait(message)
                except queue.Full:
                    print(f"[!] 队列已满，丢弃帧")
                
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
            
            elif isinstance(message, str):
                print(f"[T] 收到文本消息: {message}")
    
    except websockets.exceptions.ConnectionClosed:
        print(f"[-] 客户端断开连接: {websocket.remote_address}")
    except Exception as e:
        print(f"[!] 发生错误: {e}")
    finally:
        connected_clients.discard(websocket)
        print(f"[*] 当前在线客户端: {len(connected_clients)}")
        
        # 等待编码线程完成
        print("[*] 正在完成MP4编码...")
        encoder_thread.join(timeout=10)
        print(f"[+] 录制完成: {output_filename}")


async def main():
    """启动 WebSocket 服务器"""
    server = await websockets.serve(
        handle_client,
        '0.0.0.0',
        args.port,
        max_size=10 * 1024 * 1024
    )
    
    print("=" * 60)
    print("✅ H264 -> MP4 服务器启动成功")
    print("=" * 60)
    print(f"本地地址: ws://localhost:{args.port}/stream")
    print(f"网络地址: ws://0.0.0.0:{args.port}/stream")
    print("=" * 60)
    print("使用 cloudflared 内网穿透:")
    print(f"  cloudflared.exe tunnel --url http://localhost:{args.port}")
    print("=" * 60)
    print("等待客户端连接...")
    print("-" * 60)
    
    # 启动统计线程
    threading.Thread(target=stats_thread, daemon=True).start()
    
    async with server:
        await server.wait_closed()


# ===================== 启动 =====================
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WebSocket H.264 裸流服务器 - 直接录制为MP4")
    parser.add_argument("--port", type=int, default=8765, help="监听端口")
    args = parser.parse_args()
    
    # 检查FFmpeg是否可用
    try:
        subprocess.run(['ffmpeg', '-version'], capture_output=True)
    except FileNotFoundError:
        print("❌ 错误: 未找到FFmpeg，请安装FFmpeg并添加到PATH")
        exit(1)
    
    # 创建输出目录
    if not os.path.exists("output"):
        os.makedirs("output")
    os.chdir("output")
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[+] 服务器已停止")
        RUN_FLAG = False