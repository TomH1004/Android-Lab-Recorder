# LabRecorder

A simple Android app to record heart rate (HR) and RR-interval data simultaneously from two Polar BLE heart rate sensors.

***

## How to Use

1.  **Install the App**: You can install the app directly using the `app-release.apk` file in the repository. Grant the necessary Bluetooth permissions when prompted.
2.  **Enter Group ID**: Type a unique name for your recording session in the **Group ID** field.
3.  **Scan & Connect**: The app automatically scans for Polar devices. Once found, they will be auto-assigned. Click **Connect** and wait for the status to change to "Connected".
4.  **Record**: Click **Start Recording**. You can log events using the **Start/Stop Interval** and **Mark Timestamp** buttons.
5.  **Stop**: Click **Stop Recording** to save the session.

***

## Data Output

Recorded data is saved as `.csv` files on your device. You can find them in the following directory:

`Documents/LabRecorder/<Your_Group_ID>/`
