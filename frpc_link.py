import subprocess
import sys
import os
import time

# ============ 配置 =============
FRPC_EXE = r"frpc.exe"          # ← 改成你 frpc.exe 的真实完整路径
FRPC_ARG = ""  # 去掉 -f，直接传参数
# ===================================================


def start_tunnel():
    # 1) 检查文件存不存在
    print(f"[*] 检查文件: {FRPC_EXE}")
    if not os.path.isfile(FRPC_EXE):
        print(f"❌ 文件不存在！")
        # 尝试在工作目录找
        alt = os.path.join(os.getcwd(), "frpc.exe")
        print(f"[*] 工作目录下有没有: {alt} -> 存在={os.path.isfile(alt)}")
        return None

    # 2) 不隐藏窗口，让你能看到 frpc 报什么错
    print(f"[*] 启动: {FRPC_EXE} -f {FRPC_ARG}")
    proc = subprocess.Popen(
        [FRPC_EXE, "-f", FRPC_ARG],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,       # 合并 stderr 到 stdout
    )

    # 3) 等一下，看看有没有立即退出
    time.sleep(5)

    if proc.poll() is not None:
        # 进程退出了，读输出
        out = proc.stdout.read().decode("utf-8", errors="ignore")
        print(f"❌ 隧道启动失败，退出码: {proc.returncode}")
        print(f"❌ 输出内容:\n{out}")
        return None

    print("✅ 樱花隧道已启动！")
    return proc


if __name__ == "__main__":
    start_tunnel()
    input("按回车退出...")
