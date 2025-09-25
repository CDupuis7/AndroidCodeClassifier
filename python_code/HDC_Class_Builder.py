import os
import time
import threading
import numpy as np
import torch
from hashlib import md5
from typing import Dict, List
from androguard.core.bytecodes.apk import APK
from androguard.core.bytecodes.dvm import DalvikVMFormat

#power monitoring
try:
    import pynvml
    pynvml.nvmlInit()
    nvml = pynvml.nvmlDeviceGetHandleByIndex(0)
except Exception as e:
    print(f"[WARN] NVML not available: {e}")
    pynvml = None
    nvml = None

#parameters
d = 2048
max_ops = 10000
max_apks = 300 #per class
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

def set_seed(seed: int = 1337):
    np.random.seed(seed)
    torch.manual_seed(seed)

#opcode and method cache for smartphone coherence
opcode_cache: Dict[tuple, torch.Tensor] = {}
method_cache: Dict[tuple, torch.Tensor] = {}

#setting seed
def _md5_seed(s: str) -> int:
    return int(md5(s.encode("utf-8")).hexdigest()[:8], 16)

def _bipolar_from_seed(seed: int, D: int) -> torch.Tensor:
    rng = np.random.RandomState(seed)
    v = rng.randint(0, 2, size=D, dtype=np.uint8).astype(np.int8)
    v[v == 0] = -1
    return torch.from_numpy(v).to(torch.int8).to(device)

#opcode HV builder
def opcode_vec(op: str, D: int = d) -> torch.Tensor:
    key = (op, D)
    if key in opcode_cache:
        return opcode_cache[key]
    seed = _md5_seed("OPC::" + op)
    v = _bipolar_from_seed(seed, D)
    opcode_cache[key] = v
    return v

#method HV builder
def method_name_to_vec(method_name: str, D: int = d) -> torch.Tensor:
    key = (method_name, D)
    if key in method_cache:
        return method_cache[key]
    seed = _md5_seed("MTH::" + method_name)
    v = _bipolar_from_seed(seed, D)
    method_cache[key] = v
    return v

#binding function
def bind(a: torch.Tensor, b: torch.Tensor) -> torch.Tensor:
    return a * b

#bundling function (sign)
def sign_bundle(acc: torch.Tensor) -> torch.Tensor:
    out = torch.empty_like(acc, dtype=torch.int8, device=device)
    out[acc > 0] = 1
    out[acc < 0] = -1
    if torch.any(acc == 0):
        tie = _bipolar_from_seed(0xC0FFEE, acc.numel())
        out[acc == 0] = tie[acc == 0]
    return out

#opcode/method extraction
def extract_opcodes_by_method_fast(apk_path: str, max_total_ops: int = max_ops) -> Dict[str, List[str]]:
    try:
        a = APK(apk_path)
        dex_list = a.get_all_dex()
        if not dex_list:
            main_dex = a.get_dex()
            if not main_dex:
                print(f"[SKIP] No dex found in {apk_path}")
                return {}
            dex_list = [main_dex]

        method_to_opcodes: Dict[str, List[str]] = {}
        total_ops = 0
        for dex_bytes in dex_list:
            dvm = DalvikVMFormat(dex_bytes)
            for method in dvm.get_methods():
                code = method.get_code()
                if code is None:
                    continue
                try:
                    bc = code.get_bc()
                    op_list = [ins.get_name() for ins in bc.get_instructions()]
                    if not op_list:
                        continue
                    method_name = method.get_class_name() + "->" + method.get_name()
                    remaining = max_total_ops - total_ops
                    if remaining <= 0:
                        return method_to_opcodes
                    clipped = op_list[:remaining]
                    method_to_opcodes[method_name] = clipped
                    total_ops += len(clipped)
                    if total_ops >= max_total_ops:
                        return method_to_opcodes
                except Exception:
                    continue
        return method_to_opcodes
    except Exception as e:
        print(f"[ERROR] Failed to parse {apk_path}: {e}")
        return {}

#encoding method
def encode_app_from_method_opcodes(method_op_map: Dict[str, List[str]], D: int = d,
                                   max_ops_per_app: int = max_ops) -> torch.Tensor:
    acc = torch.zeros(D, dtype=torch.int32, device=device)
    ops_used = 0
    for method_name, op_list in method_op_map.items():
        if ops_used >= max_ops_per_app:
            break
        mvec = method_name_to_vec(method_name, D)
        remaining = max_ops_per_app - ops_used
        for op in op_list[:remaining]:
            acc += bind(opcode_vec(op, D), mvec).to(torch.int32)
            ops_used += 1
            if ops_used >= max_ops_per_app:
                break
    return sign_bundle(acc)

#building malware and benign class vectors together
def build_class_vectors_together(benign_folder: str, malware_folder: str,
                                 D: int = d, max_apks: int = max_apks):
    benign_acc = torch.zeros(D, dtype=torch.int32, device=device)
    malware_acc = torch.zeros(D, dtype=torch.int32, device=device)

    benign_files = sorted(f for f in os.listdir(benign_folder) if f.lower().endswith(".apk"))
    malware_files = sorted(f for f in os.listdir(malware_folder) if f.lower().endswith(".apk"))
    num_files = min(len(benign_files), len(malware_files), max_apks)

    for i in range(num_files):
        bpath = os.path.join(benign_folder, benign_files[i])
        mpath = os.path.join(malware_folder, malware_files[i])

        print(f"Processing benign {benign_files[i]} and malware {malware_files[i]}")

        b_map = extract_opcodes_by_method_fast(bpath)
        m_map = extract_opcodes_by_method_fast(mpath)

        if b_map:
            benign_acc += encode_app_from_method_opcodes(b_map, D).to(torch.int32)
        if m_map:
            malware_acc += encode_app_from_method_opcodes(m_map, D).to(torch.int32)

    benign_class_vector = sign_bundle(benign_acc)
    malware_class_vector = sign_bundle(malware_acc)
    return benign_class_vector, malware_class_vector

#power sampling
def sample_gpu_power(interval: float, readings: list, stop_flag: list):
    if not (pynvml and nvml):
        return
    while not stop_flag[0]:
        try:
            p = pynvml.nvmlDeviceGetPowerUsage(nvml) / 1000.0  # W
            readings.append(p)
        except:
            pass
        time.sleep(interval)

if __name__ == "__main__":
    benign_folder = "your_folder"
    malware_folder = "your_folder"

    set_seed(42)

    start_time = time.time()
    if device.type == "cuda":
        torch.cuda.reset_peak_memory_stats()

    power_readings = []
    stop_flag = [False]
    if pynvml and nvml:
        t = threading.Thread(target=sample_gpu_power, args=(0.2, power_readings, stop_flag))
        t.start()

    #build class HVs
    benign_vec, malware_vec = build_class_vectors_together(benign_folder, malware_folder)

    end_time = time.time()
    elapsed = end_time - start_time

    if device.type == "cuda":
        mem_alloc = torch.cuda.memory_allocated() / (1024 ** 2)
        max_mem = torch.cuda.max_memory_allocated() / (1024 ** 2)
    else:
        mem_alloc = max_mem = 0

    if pynvml and nvml:
        stop_flag[0] = True
        t.join()
        if power_readings:
            avg_power = sum(power_readings) / len(power_readings)
        else:
            avg_power = -1
    else:
        avg_power = -1

    #results
    print("\nClass vectors built")
    print("Benign (first 32):", benign_vec[:32].cpu().numpy())
    print("Malware (first 32):", malware_vec[:32].cpu().numpy())

    print(f"\nTraining time: {elapsed:.2f} sec")
    print(f"GPU memory allocated: {mem_alloc:.2f} MB (max {max_mem:.2f} MB)")
    if avg_power >= 0:
        print(f"Avg GPU power usage: {avg_power:.2f} W (sampled every 0.2s)")
    else:
        print("GPU power usage: [NVML not available]")

    np.save("benign_class_vector.npy", benign_vec.cpu().numpy().astype(np.int8))
    np.save("malware_class_vector.npy", malware_vec.cpu().numpy().astype(np.int8))
    print("\nSaved to benign_class_vector.npy and malware_class_vector.npy")
