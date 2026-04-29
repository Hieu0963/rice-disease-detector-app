# 🌾 Rice Leaf Doctor

**Rice Leaf Doctor** is an open-source Android application that leverages Deep Learning to automatically diagnose common diseases on rice leaves. The system is designed to analyze images and perform on-device inference, enabling farmers to detect diseases early and receive treatment recommendations directly in the field without relying on an internet connection.
<img width="358" height="772" alt="image" src="https://github.com/user-attachments/assets/f6cd6fe7-ffce-4c28-a9e2-397270bdcef2" />

---

## ✨ Key Features

* **Offline Diagnosis (Edge AI):** Integrates a TensorFlow Lite model for fast on-device inference, achieving an average response time of 25-30ms per image on a mobile device.
* **5-Class Classification:** Provides high-accuracy identification of healthy leaves and four common diseases: *Bacterial Leaf Blight, Brown Spot, Rice Blast, and Tungro Virus*.
* **Flexible Image Input:** Users can quickly capture images directly using the device's camera or upload existing photos from the local gallery.
* **Data Synchronization (Firebase):** Manages diagnostic history locally and automatically syncs to Firebase when an internet connection is available. Detailed disease information, warnings, and practical prevention measures are also continuously updated from the Firebase database.
* **Adaptive UI:** Supports multilingual display (English and Vietnamese) and automatically switches between Light and Dark Mode based on the Android system settings.

---

## 🛠 System Architecture & Technology

* **Mobile Development (Frontend):** Native Android application built with Kotlin, utilizing `ImageDecoder` and `MediaStore` for robust image processing.
* **AI Core:** Convolutional Neural Network (CNN) architecture utilizing an *EfficientNet-Lite0* backbone, trained via Transfer Learning.
* **Edge Deployment:** The trained model is converted to a `.tflite` format and deployed using the TensorFlow Lite framework.
* **Cloud Infrastructure (Backend):** Firebase is utilized as the backend platform for storing disease knowledge and syncing user history.

---

## 📊 Model Performance

The deep learning model was fine-tuned and trained on a carefully curated and augmented dataset of over **4,200 rice leaf images**. Performance highlights of the EfficientNet-Lite0 model include:

* **Accuracy:** Achieved approximately **92.3%** on the validation set.
* **Macro-Average Precision (AP):** Reached **0.97**, indicating strong and consistent classification capabilities across all disease categories.

---

## 🎓 Project & Author Information

This project is a Graduation Thesis from the Faculty of Advanced Education, majoring in Computer Engineering & Technology, at **Ho Chi Minh City University of Technology and Education (HCMUTE)**.

* **Student Developers:** Trinh Le Minh Hieu and Huynh Tan Phat.
* **Advisor:** PhD. Huynh The Thien.

---

## ⚖️ Statement of Commitment

All source code, software architecture, and presented contents are the results of independent research and personal effort. This project does not contain any act of plagiarism or unauthorized use of content, data, or results from previously published works.
