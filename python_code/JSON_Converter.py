import numpy as np
import json

#python input files
BENIGN_NPY = "benign_class_vector.npy"
MALWARE_NPY = "malware_class_vector.npy"

#json output files
BENIGN_JSON = "benign_class_vector.json"
MALWARE_JSON = "malware_class_vector.json"

def npy_to_json(npy_file: str, json_file: str):
    arr = np.load(npy_file)
    arr_list = arr.tolist()
    
    #saved as json
    with open(json_file, "w") as f:
        json.dump(arr_list, f)
    print(f"Saved {json_file}")

if __name__ == "__main__":
    npy_to_json(BENIGN_NPY, BENIGN_JSON)
    npy_to_json(MALWARE_NPY, MALWARE_JSON)
