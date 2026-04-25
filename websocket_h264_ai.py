#!/usr/bin/env python3
"""
WebSocket H.264 实时解码显示服务器 v3

v3 改动:
  - 修复: 跳过 SEI/AUD 等非视频 NALU, 消除 avcodec_send_packet 错误
  - 新增: JSON 配置文件, 首次运行自动生成 server_config.json
  - 新增: 可配置 CV 窗口开关、录制开关、定时截图
  - 新增: 录制文件大小限制, 超出自动分片
"""

import asyncio
import websockets
import os
import sys
import time
import json
import argparse
import cv2
import numpy as np
import threading
import queue

try:
    import av
except ImportError:
    print("[!] 请先安装 PyAV: pip install av")
    raise SystemExit(1)


# ================================================================
#  配置系统
# ================================================================

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILENAME = "server_config.json"

DEFAULT_CONFIG = {
    "server": {
        "host": "0.0.0.0",
        "port": 8765,
        "max_message_mb": 2,
    },
    "display": {
        "enabled": True,
        "window_name": "H264-Live",
        "show_overlay": True,
    },
    "recording": {
        "enabled": True,
        "output_dir": "output",
        "max_file_mb": 0,
        "file_prefix": "h264",
    },
    "screenshot": {
        "enabled": False,
        "interval_seconds": 4.0,
        "output_dir": "screenshots",
        "filename": "capture.jpg",
        "quality": 85,
    },
    "decode": {
        "queue_size": 8000,
    },
    "stats": {
        "enabled": True,
        "interval": 100,
    },
}

cfg = {}


def load_config(path=None):
    """加载配置, 缺失键用默认值补齐, 无文件则自动创建"""
    global cfg

    config_path = (
        os.path.join(SCRIPT_DIR, path)
        if path
        else os.path.join(SCRIPT_DIR, CONFIG_FILENAME)
    )

    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                user_cfg = json.load(f)
            print(f"[+] 已加载配置: {config_path}")
        except json.JSONDecodeError as e:
            print(f"[!] 配置文件 JSON 格式错误: {e}, 使用默认配置")
            user_cfg = {}
    else:
        user_cfg = {}
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_CONFIG, f, indent=4, ensure_ascii=False)
        print(f"[+] 已生成默认配置文件: {config_path}")

    def deep_merge(base, override):
        result = {}
        for k in set(list(base.keys()) + list(override.keys())):
            if k in base and k in override:
                if isinstance(base[k], dict) and isinstance(override[k], dict):
                    result[k] = deep_merge(base[k], override[k])
                else:
                    result[k] = override[k]
            elif k in base:
                result[k] = base[k]
            else:
                result[k] = override[k]
        return result

    cfg = deep_merge(DEFAULT_CONFIG, user_cfg)

    # 基本校验
    port = cfg["server"]["port"]
    if not isinstance(port, int) or not (1 <= port <= 65535):
        print(f"[!] 无效端口 {port}, 回退到 8765")
        cfg["server"]["port"] = 8765

    interval = cfg["screenshot"].get("interval_seconds", 4.0)
    if interval < 0.5:
        print("[!] 截图间隔最小 0.5 秒")
        cfg["screenshot"]["interval_seconds"] = 0.5


# ================================================================
#  全局状态
# ================================================================

connected_clients = set()
output_file = None
file_lock = asyncio.Lock()
rec_file_size = 0
rec_file_seq = 0
rec_file_base = ""

START_CODE = b"\x00\x00\x00\x01"
client_buffers = {}

total_ws_msgs = 0
total_i_frames = 0
total_bytes = 0
start_time = None

is_running = True
nalu_queue = None

_latest_lock = threading.Lock()
_latest_bgr = None
_latest_id = 0
_latest_is_key = False

# H.264 中解码器能处理(且需要)的 NALU 类型
# 1-5: VCL 视频切片 (P帧/B帧/IDR帧)
# 7:   SPS — 解码器用它初始化参数
# 8:   PPS — 解码器用它初始化参数
DECODABLE_TYPES = {1, 2, 3, 4, 5, 7, 8}


# ================================================================
#  解码线程 — 用 av.CodecContext 逐包解码
# ================================================================

def decode_thread_body():
    """
    从 nalu_queue 取 NALU, 直接喂给 CodecContext 解码。
    跳过 SEI(6)/AUD(9)/EndOfSeq(10)/Filler(12) 等非视频 NALU。
    """
    global _latest_bgr, _latest_id, _latest_is_key

    codec = None
    last_sps = None
    decoded_count = 0
    err_streak = 0

    while is_running:
        try:
            nalu_data, nalu_type = nalu_queue.get(timeout=0.03)
        except queue.Empty:
            continue

        # —— 跳过解码器不认识的 NALU 类型 ——
        # 这就是修复 avcodec_send_packet Invalid data 的关键
        if nalu_type not in DECODABLE_TYPES:
            continue

        # —— SPS: 初始化或重建解码器 ——
        if nalu_type == 7:
            need_reinit = (codec is None) or (nalu_data != last_sps)
            if need_reinit:
                try:
                    new_codec = av.CodecContext.create("h264", "r")
                    new_codec.open()

                    # flush 旧解码器残留帧
                    if codec is not None:
                        try:
                            for f in codec.decode(None):
                                bgr = f.to_ndarray(format="bgr24")
                                with _latest_lock:
                                    _latest_bgr = bgr
                                    _latest_id += 1
                                    _latest_is_key = False
                                decoded_count += 1
                        except Exception:
                            pass
                        codec.close()

                    codec = new_codec
                    last_sps = nalu_data
                    err_streak = 0
                    print(f"[DEC] 解码器就绪 (已累计解码 {decoded_count} 帧)")
                except Exception as e:
                    print(f"[DEC] 初始化失败: {e}")
                    codec = None

        if codec is None:
            continue

        # —— 解码视频帧 ——
        try:
            pkt = av.Packet(nalu_data)
            for frame in codec.decode(pkt):
                bgr = frame.to_ndarray(format="bgr24")
                with _latest_lock:
                    _latest_bgr = bgr
                    _latest_id += 1
                    try:
                        _latest_is_key = frame.pict_type.name == "I"
                    except Exception:
                        _latest_is_key = False
                decoded_count += 1
                err_streak = 0
        except Exception as e:
            err_streak += 1
            if err_streak <= 2:
                print(f"[DEC] 解码异常 (type={nalu_type}): {e}")
            if err_streak > 50:
                print("[DEC] 连续错误过多, 强制重置")
                try:
                    codec.close()
                except Exception:
                    pass
                codec = None
                last_sps = None
                err_streak = 0

    # 退出时 flush
    if codec:
        try:
            for f in codec.decode(None):
                bgr = f.to_ndarray(format="bgr24")
                with _latest_lock:
                    _latest_bgr = bgr
                    _latest_id += 1
                decoded_count += 1
        except Exception:
            pass
        try:
            codec.close()
        except Exception:
            pass

    with _latest_lock:
        _latest_bgr = None
    print(f"[DEC] 解码线程结束, 共解码 {decoded_count} 帧")


# ================================================================
#  显示线程 — cv2.imshow + 定时截图
# ================================================================

def save_screenshot(frame):
    """保存一帧截图(同名覆盖)"""
    ss = cfg["screenshot"]
    ss_dir = ss["output_dir"]
    os.makedirs(ss_dir, exist_ok=True)
    filepath = os.path.join(ss_dir, ss["filename"])
    quality = ss.get("quality", 85)
    ext = os.path.splitext(filepath)[1].lower()

    params = []
    if ext in (".jpg", ".jpeg"):
        params = [cv2.IMWRITE_JPEG_QUALITY, quality]
    elif ext == ".png":
        params = [cv2.IMWRITE_PNG_COMPRESSION, max(0, min(9, 9 - quality // 12))]

    try:
        cv2.imwrite(filepath, frame, params)
        print(f"[SS] 截图已保存: {filepath} ({len(frame[0])}x{len(frame)})")
    except Exception as e:
        print(f"[SS] 保存失败: {e}")


def display_thread_body():
    """
    显示窗口 + 定时截图。
    有新帧就刷新, 没新帧就保持上一帧(不会闪黑屏)。
    """
    global is_running

    win = cfg["display"].get("window_name", "H264-Live")
    overlay = cfg["display"].get("show_overlay", True)
    ss_on = cfg["screenshot"]["enabled"]
    ss_sec = cfg["screenshot"].get("interval_seconds", 4.0)

    cv2.namedWindow(win, cv2.WINDOW_NORMAL)

    shown_id = -1
    fps_count = 0
    fps_time = time.time()
    disp_fps = 0.0
    last_ss = 0.0

    while is_running:
        with _latest_lock:
            bgr = _latest_bgr
            fid = _latest_id
            is_key = _latest_is_key

        if bgr is not None and fid != shown_id:
            shown_id = fid
            fps_count += 1

            # 显示用副本(避免在原帧上画字影响截图)
            show_img = bgr.copy() if overlay else bgr

            if overlay:
                label = f"#{fid}"
                if is_key:
                    label += " [KEY]"
                cv2.putText(
                    show_img, label, (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2,
                )
                cv2.putText(
                    show_img, f"{disp_fps:.1f}fps", (10, 58),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 200, 255), 2,
                )

            cv2.imshow(win, show_img)

            # 截图
            if ss_on:
                now = time.time()
                if now - last_ss >= ss_sec:
                    save_screenshot(bgr)  # 用原始帧截图(无文字)
                    last_ss = now

        elif bgr is None:
            # 真正没帧 → 显示等待提示
            placeholder = np.zeros((480, 640, 3), dtype=np.uint8)
            cv2.putText(
                placeholder, "waiting for stream...", (40, 240),
                cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 180, 0), 2,
            )
            cv2.imshow(win, placeholder)

        key = cv2.waitKey(1) & 0xFF
        if key in (27, ord("q"), ord("Q")):
            print("[GUI] 用户按键退出")
            is_running = False
            break

        # 每 2 秒刷新 FPS 数值
        now = time.time()
        dt = now - fps_time
        if dt >= 2.0:
            disp_fps = fps_count / dt
            fps_count = 0
            fps_time = now

    cv2.destroyAllWindows()
    print("[GUI] 显示线程结束")


# ================================================================
#  AI 识图占位
# ================================================================

def do_ai_recognition(bgr_frame: np.ndarray, frame_id: int):
    """在这里接入你的 AI 模型"""
    # print(f"[AI] frame#{frame_id} shape={bgr_frame.shape}")
    pass


# ================================================================
#  WebSocket 客户端处理
# ================================================================

async def handle_client(websocket):
    global output_file, total_ws_msgs, total_i_frames, total_bytes, start_time
    global rec_file_size, rec_file_seq, rec_file_base

    cid = id(websocket)
    client_buffers[cid] = b""

    print(f"\n[+] 客户端连接: {websocket.remote_address}")
    connected_clients.add(websocket)
    print(f"[*] 在线: {len(connected_clients)}")

    if start_time is None:
        start_time = time.time()

    rec_cfg = cfg["recording"]
    rec_on = rec_cfg["enabled"]
    max_mb = rec_cfg.get("max_file_mb", 0)

    try:
        # —— 开始录制 ——
        if rec_on:
            rec_dir = rec_cfg["output_dir"]
            os.makedirs(rec_dir, exist_ok=True)
            ts = time.strftime("%Y%m%d_%H%M%S")
            prefix = rec_cfg.get("file_prefix", "h264")
            rec_file_base = f"{prefix}_{ts}"
            rec_file_seq = 0
            fname = f"{rec_file_base}.h264"

            async with file_lock:
                output_file = open(os.path.join(rec_dir, fname), "wb")
                rec_file_size = 0
                print(f"[+] 录制开始: {fname}")

        msg_count = 0

        async for message in websocket:
            if not isinstance(message, bytes):
                continue

            msg_count += 1
            total_ws_msgs += 1
            total_bytes += len(message)

            # —— 写文件 ——
            if rec_on:
                async with file_lock:
                    if output_file:
                        output_file.write(message)
                        output_file.flush()
                        rec_file_size += len(message)

                        # 文件大小分片轮转
                        if max_mb > 0 and rec_file_size >= max_mb * 1024 * 1024:
                            output_file.close()
                            rec_file_seq += 1
                            rec_dir = rec_cfg["output_dir"]
                            fname = (
                                f"{rec_file_base}"
                                f"_p{rec_file_seq:03d}.h264"
                            )
                            output_file = open(
                                os.path.join(rec_dir, fname), "wb"
                            )
                            rec_file_size = 0
                            print(f"[+] 录制分片: {fname}")

            # —— NALU 拆分 ——
            client_buffers[cid] += message

            while True:
                idx = client_buffers[cid].find(START_CODE, 4)
                if idx == -1:
                    break

                nalu = client_buffers[cid][:idx]
                client_buffers[cid] = client_buffers[cid][idx:]

                if len(nalu) < 5:
                    continue

                nalu_type = nalu[4] & 0x1F

                if nalu_type == 5:
                    total_i_frames += 1

                # 喂入解码队列(满了丢最旧的)
                try:
                    nalu_queue.put_nowait((nalu, nalu_type))
                except queue.Full:
                    try:
                        nalu_queue.get_nowait()
                    except queue.Empty:
                        pass
                    try:
                        nalu_queue.put_nowait((nalu, nalu_type))
                    except queue.Full:
                        pass

            # —— 统计 ——
            st = cfg["stats"]
            if (
                st.get("enabled", True)
                and msg_count % st.get("interval", 100) == 0
            ):
                elapsed = time.time() - start_time if start_time else 1
                print(
                    f"[*] 消息:{msg_count}  I帧:{total_i_frames}  "
                    f"{total_bytes / 1048576:.1f}MB  "
                    f"{msg_count / elapsed:.0f}msg/s"
                )

    except websockets.exceptions.ConnectionClosed:
        print(f"[-] 客户端断开: {websocket.remote_address}")
    except Exception as e:
        print(f"[!] 错误: {e}")
        import traceback
        traceback.print_exc()
    finally:
        if cid in client_buffers:
            del client_buffers[cid]
        connected_clients.discard(websocket)
        print(f"[*] 在线: {len(connected_clients)}")

        async with file_lock:
            if output_file:
                output_file.close()
                output_file = None
                print("[+] 录制文件已关闭")

        if len(connected_clients) == 0:
            total_ws_msgs = 0
            total_i_frames = 0
            total_bytes = 0
            start_time = None
            print("[*] 等待新连接...")


# ================================================================
#  启动
# ================================================================

async def main():
    srv = cfg["server"]
    server = await websockets.serve(
        handle_client,
        srv["host"],
        srv["port"],
        max_size=srv.get("max_message_mb", 2) * 1024 * 1024,
    )

    print("=" * 55)
    print("  H.264 实时服务器 v3")
    print("=" * 55)
    print(f"  地址:      ws://{srv['host']}:{srv['port']}")
    d = cfg["display"]
    print(f"  CV窗口:    {'开启' if d['enabled'] else '关闭'}"
          f"{'  (叠加信息)' if d['enabled'] and d['show_overlay'] else ''}")
    r = cfg["recording"]
    print(f"  录制保存:  {'开启' if r['enabled'] else '关闭'}"
          f"{'  dir=' + r['output_dir'] if r['enabled'] else ''}"
          f"{'  上限' + str(r['max_file_mb']) + 'MB' if r['enabled'] and r['max_file_mb'] else ''}")
    s = cfg["screenshot"]
    if s["enabled"]:
        print(f"  截图:      开启  {s['interval_seconds']}秒/次"
              f"  → {s['output_dir']}/{s['filename']}")
    else:
        print(f"  截图:      关闭")
    print(f"  解码队列:  {cfg['decode'].get('queue_size', 8000)}")
    print("=" * 55)
    print("  cloudflared:")
    print(f"    cloudflared tunnel --url http://localhost:{srv['port']}")
    print("=" * 55)
    print("  等待客户端连接...")
    print("-" * 55)

    async with server:
        await server.wait_closed()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="H.264 实时服务器 v3")
    parser.add_argument("--port", type=int, default=None,
                        help="覆盖配置文件中的端口")
    parser.add_argument("--config", type=str, default=None,
                        help="指定配置文件路径")
    cli = parser.parse_args()

    load_config(cli.config)

    # 命令行参数覆盖配置文件
    if cli.port is not None:
        cfg["server"]["port"] = cli.port

    # 初始化解码队列(必须在 load_config 之后)
    nalu_queue = queue.Queue(maxsize=cfg["decode"].get("queue_size", 8000))

    # 只在需要解码时才启动解码线程
    need_decode = cfg["display"]["enabled"] or cfg["screenshot"]["enabled"]
    if need_decode:
        threading.Thread(target=decode_thread_body, name="decode", daemon=True).start()

    # 只在需要显示时才启动显示线程
    if cfg["display"]["enabled"]:
        threading.Thread(target=display_thread_body, name="display", daemon=True).start()

    # 主线程: WebSocket 服务器
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    finally:
        is_running = False
        print("\n[+] 服务器已停止")
