# Android Malware Detection with Inference Application
# Date of Creation: 09/05/2025

## Overview
This repository contains the source code and pipeline for a lightweight Android malware detection framework based on **Hyperdimensional Computing (HDC)**. The framework leverages **opcode–method binding** from Dalvik bytecode to build high-dimensional hypervectors for benign and malware Android APKs.  

Our approach uses one-shot learning to generate class hypervectors for each category and classifies unseen APKs through cosine similarity. Unlike heavy ML models (e.g., RF or SVM), the HDC framework is memory- and energy-efficient, making it suitable for **real-time, on-device deployment**.  

This repository also includes the code for our **Samsung smartphone application**, which integrates the inference pipeline into an Android UI for on-device testing. The app allows users to select APKs, choose dimensionality, and run classification while logging metrics such as:  
- Predicted class and similarity scores  
- Inference latency  
- Battery usage and drop  
- Peak memory consumption  

## Repository Contents
- **HDC_Inference.py** – Runs inference on APKs using precomputed class vectors.
- **Opcode_Method_Crawler.py** – Extracts opcode features from benign and malware APKs and generates lookup tables.
- **HDC_Class_Builder.py** – Builds benign and malware class vectors together, with GPU support and power/memory tracking.
- **JSON_Converter.py** – Converts `.npy` class vectors into `.json` for mobile deployment.

## Dataset
⚠️ **Important:** This repository does not contain the dataset due to licensing restrictions.  
- The benign and malware APKs used in training and evaluation come from **AndroZoo**.  
- To reproduce experiments, you must **request dataset access directly from AndroZoo**: [AndroZoo Project](https://androzoo.uni.lu/).  
- We thank the AndroZoo team for providing a large-scale, high-quality dataset for the research community.  

## Key Features
- **Opcode + Method Encoding:** Each opcode is bound to its enclosing method, capturing behavioral context.  
- **One-Shot Learning:** Only a single training pass is required to generate class hypervectors.  
- **Cosine Similarity Classification:** Fast, deterministic inference with normalized similarity scores.  
- **Mobile Integration:** Custom Samsung Android app for on-device testing.  
- **Efficiency:** Competitive accuracy with much lower inference time, memory usage, and energy consumption compared to RF and SVM baselines.  

## Experimental Highlights
- HDC classifier accuracy scales with dimensionality (D=512 → 70%, D=2048 → 78%).  
- RF and SVM achieve higher accuracy (86–82%) but with **10–100× higher inference time and energy consumption**.  
- On Samsung hardware, HDC achieves:  
  - ~104 ms inference time at D=2048  
  - Only 22.9 MB peak memory  
  - 10,000 inferences with <5% battery drop