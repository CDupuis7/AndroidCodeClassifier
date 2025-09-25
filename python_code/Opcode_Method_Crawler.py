import os
import json
import numpy as np
from HDC_Class_Builder import opcode_vec, extract_opcodes_by_method_fast

#parameters
d = 2048
max_apk = 300 #per class
benign_folder = "your_folder"
malware_folder = "your_folder"

def hv_to_list(hv: np.ndarray):
    return hv.astype(int).tolist()

def collect_opcodes(apk_folder: str, max_apks: int):
    opcodes = set()
    files = sorted(f for f in os.listdir(apk_folder) if f.lower().endswith(".apk"))
    for i, filename in enumerate(files):
        if i >= max_apks:
            break
        apk_path = os.path.join(apk_folder, filename)
        print(f"Crawling {filename} ...")
        method_op_map = extract_opcodes_by_method_fast(apk_path)
        for _, op_list in method_op_map.items():
            opcodes.update(op_list)
    return opcodes

if __name__ == "__main__":
    all_opcodes = set()

    for folder in [benign_folder, malware_folder]:
        ops = collect_opcodes(folder, max_apk)
        all_opcodes.update(ops)

    print(f"\nCollected {len(all_opcodes)} unique opcodes.")

    export_dict = {
        "opcodes": {op: hv_to_list(opcode_vec(op, d)) for op in sorted(all_opcodes)}
    }

    with open("hv_lookup.json", "w") as f:
        json.dump(export_dict, f)

    print("\nExported hv_lookup.json (opcodes only)")
