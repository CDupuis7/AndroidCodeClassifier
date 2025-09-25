import os
import numpy as np
from hashlib import md5
from typing import List, Dict, Tuple
from androguard.core.bytecodes.apk import APK
from androguard.core.bytecodes.dvm import DalvikVMFormat
from numpy.linalg import norm
import random

#parameters
d = 2048
max_opcodes = 10000
num_test_files = 200 #per class
tail_length = 300 #per class

benign_folder = "your_folder"
malware_folder = "your_folder"

#load class vectors
benign_class_vector = np.load("benign_class_vector.npy").astype(np.int8)
malware_class_vector = np.load("malware_class_vector.npy").astype(np.int8)

#opcode and method cache for smartphone coherence
opcode_cache: Dict[Tuple[str, int], np.ndarray] = {}
method_cache: Dict[Tuple[str, int], np.ndarray] = {}

#setting seed
def _md5_seed(s: str) -> int:
    return int(md5(s.encode("utf-8")).hexdigest()[:8], 16)

def _bipolar_from_seed(seed: int, D: int) -> np.ndarray:
    rng = np.random.RandomState(seed)
    v = rng.randint(0, 2, size=D, dtype=np.uint8).astype(np.int8)
    v[v == 0] = -1
    return v

#opcode HV builder
def opcode_vec(op: str, D: int = d) -> np.ndarray:
    key = (op, D)
    if key in opcode_cache:
        return opcode_cache[key]
    seed = _md5_seed("OPC::" + op)
    v = _bipolar_from_seed(seed, D)
    opcode_cache[key] = v
    return v

#method HV builder
def method_name_to_vec(method_name: str, D: int = d) -> np.ndarray:
    key = (method_name, D)
    if key in method_cache:
        return method_cache[key]
    seed = _md5_seed("MTH::" + method_name)
    v = _bipolar_from_seed(seed, D)
    method_cache[key] = v
    return v

#binding function
def bind(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return a * b

#bundling function (sign)
def sign_bundle(acc: np.ndarray) -> np.ndarray:
    out = np.empty_like(acc, dtype=np.int8)
    out[acc > 0] = 1
    out[acc < 0] = -1
    if np.any(acc == 0):
        tie = _bipolar_from_seed(0xC0FFEE, acc.size)
        out[acc == 0] = tie[acc == 0]
    return out

#inference hypervector builder
def extract_test_vector(apk_path: str, D: int = d) -> np.ndarray:
    try:
        a = APK(apk_path)
        dex_list = a.get_all_dex()
        if not dex_list:
            dex = a.get_dex()
            if not dex:
                print(f"[SKIP] No dex found in {apk_path}")
                return np.zeros(D, dtype=np.int8)
            dex_list = [dex]

        acc = np.zeros(D, dtype=np.int32)
        total_ops = 0

        for dex_bytes in dex_list:
            if total_ops >= max_opcodes:
                break
            dvm = DalvikVMFormat(dex_bytes)
            for method in dvm.get_methods():
                if total_ops >= max_opcodes:
                    break
                code = method.get_code()
                if code is None:
                    continue
                try:
                    bc = code.get_bc()
                    method_name = method.get_class_name() + "->" + method.get_name()
                    mvec = method_name_to_vec(method_name, D)

                    remaining = max_opcodes - total_ops
                    if remaining <= 0:
                        break

                    used_here = 0
                    for ins in bc.get_instructions():
                        if used_here >= remaining:
                            break
                        op = ins.get_name()
                        acc += bind(opcode_vec(op, D), mvec)
                        used_here += 1
                        total_ops += 1
                        if total_ops >= max_opcodes:
                            break
                except Exception:
                    continue

        return sign_bundle(acc)
    except Exception as e:
        print(f"[ERROR] Failed to parse {apk_path}: {e}")
        return np.zeros(D, dtype=np.int8)

#cosine sim and classification
def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    a = a.astype(np.float32, copy=False)
    b = b.astype(np.float32, copy=False)
    na = norm(a); nb = norm(b)
    if na == 0 or nb == 0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))

#normalized cosine
def cosine01(a: np.ndarray, b: np.ndarray) -> float:
    return 0.5 * (cosine_similarity(a, b) + 1.0)

#classification function
def classify_apk(apk_path: str) -> str:
    test_vec = extract_test_vector(apk_path)
    sim_b = cosine_similarity(test_vec, benign_class_vector)
    sim_m = cosine_similarity(test_vec, malware_class_vector)
    sim_b01 = 0.5 * (sim_b + 1.0)
    sim_m01 = 0.5 * (sim_m + 1.0)

    print(f"Cosine to benign:  {sim_b:+.4f}  (0..1: {sim_b01:.4f})")
    print(f"Cosine to malware: {sim_m:+.4f}  (0..1: {sim_m01:.4f})")
    print(f"Margin (benign - malware): {(sim_b - sim_m):+.4f}")

    return "benign" if sim_b > sim_m else "malware"

#tail size for test set
def get_tail_sample(folder: str, num_files: int) -> List[str]:
    files = sorted(f for f in os.listdir(folder) if f.lower().endswith(".apk"))
    tail_files = files[-tail_length:] if tail_length > 0 else files
    if not tail_files:
        return []
    k = min(num_files, len(tail_files))
    return random.sample(tail_files, k)

if __name__ == "__main__":
    random.seed(43)

    benign_test = get_tail_sample(benign_folder, num_test_files)
    malware_test = get_tail_sample(malware_folder, num_test_files)

    total = 0
    correct = 0

    print("\nBenign Test")
    for f in benign_test:
        path = os.path.join(benign_folder, f)
        pred = classify_apk(path)
        print(f"{f}: predicted = {pred}\n")
        correct += int(pred == "benign")
        total += 1

    print("\nMalware Test")
    for f in malware_test:
        path = os.path.join(malware_folder, f)
        pred = classify_apk(path)
        print(f"{f}: predicted = {pred}\n")
        correct += int(pred == "malware")
        total += 1

    if total > 0:
        print(f"\nAccuracy: {correct}/{total} = {correct/total:.2%}")
    else:
        print("\nNo test files found to evaluate.")
