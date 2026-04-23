#!/usr/bin/env python3
"""
WebSocket H.264 裸流接收服务器

支持 cloudflared 内网穿透使用场景
"""

import asyncio
import websockets
import os
import time
import argparse

connected_clients = set()
output_file = None
file_lock = asyncio.Lock()
args = None

async def handle_client(websocket):
    """处理客户端连接（新版本 API，只有一个参数）"""
    global output_file
    
    path = websocket.request.path if hasattr(websocket, 'request') else '/'
    
    print(f"\n[+] 客户端已连接: {websocket.remote_address}")
    print(f"[*] 连接路径: {path}")
    connected_clients.add(websocket)
    print(f"[*] 当前在线客户端: {len(connected_clients)}")
    
    try:
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        filename = f"h264_stream_{timestamp}.h264"
        
        async with file_lock:
            if output_file is not None:
                output_file.close()
            output_file = open(filename, 'wb')
            print(f"[+] 开始写入文件: {filename}")
        
        frame_count = 0
        total_bytes = 0
        
        async for message in websocket:
            if isinstance(message, bytes):
                frame_size = len(message)
                frame_count += 1
                total_bytes += frame_size
                
                async with file_lock:
                    if output_file:
                        output_file.write(message)
                        output_file.flush()
                
                if len(message) > 4:
                    if (message[0] == 0 and message[1] == 0 and 
                        ((message[2] == 0 and message[3] == 1) or message[2] == 1)):
                        nal_start = 3 if message[2] == 1 else 4
                        if len(message) > nal_start:
                            nal_type = message[nal_start] & 0x1F
                            if nal_type == 5:
                                print(f"[I] 关键帧, 帧: {frame_count}, 大小: {frame_size} bytes")
                            elif nal_type == 7:
                                print(f"[S] SPS 帧, 帧: {frame_count}, 大小: {frame_size} bytes")
                            elif nal_type == 8:
                                print(f"[P] PPS 帧, 帧: {frame_count}, 大小: {frame_size} bytes")
                
                if frame_count % 100 == 0:
                    print(f"[*] 已接收 {frame_count} 帧, 总大小: {total_bytes/1024/1024:.2f} MB")
                
            elif isinstance(message, str):
                print(f"[T] 收到文本消息: {message}")
                
    except websockets.exceptions.ConnectionClosed:
        print(f"[-] 客户端断开连接: {websocket.remote_address}")
    except Exception as e:
        print(f"[!] 发生错误: {e}")
        import traceback
        traceback.print_exc()
    finally:
        connected_clients.discard(websocket)
        print(f"[*] 当前在线客户端: {len(connected_clients)}")
        
        async with file_lock:
            if output_file:
                output_file.close()
                output_file = None
                print("[+] 文件已关闭")

async def main():
    """启动 WebSocket 服务器"""
    server = await websockets.serve(
        handle_client,
        '0.0.0.0',
        args.port,
        max_size=2 * 1024 * 1024
    )
    
    print("=" * 60)
    print("WebSocket H.264 服务器已启动")
    print("=" * 60)
    print(f"本地地址: ws://localhost:{args.port}/stream")
    print(f"网络地址: ws://0.0.0.0:{args.port}/stream")
    print("=" * 60)
    print("使用 cloudflared 内网穿透:")
    print(f"  cloudflared.exe tunnel --url http://localhost:{args.port}")
    print("=" * 60)
    print("等待客户端连接...")
    print("-" * 60)
    
    async with server:
        await server.wait_closed()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WebSocket H.264 裸流接收服务器")
    parser.add_argument("--port", type=int, default=8765, help="监听端口")
    args = parser.parse_args()
    
    if not os.path.exists("output"):
        os.makedirs("output")
    os.chdir("output")
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[+] 服务器已停止")
        if output_file:
            output_file.close()
