#!/usr/bin/env python3
"""Simulates a second device on the multicast group. Receives and sends messages."""
import socket
import struct
import sys
import threading
import time

MULTICAST_GROUP = '239.255.42.1'
PORT = 9876

def listen():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('', PORT))
    mreq = struct.pack('4sL', socket.inet_aton(MULTICAST_GROUP), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    print(f"[RECEIVER] Listening on {MULTICAST_GROUP}:{PORT}...")
    while True:
        data, addr = sock.recvfrom(1024)
        msg = data.decode('utf-8')
        print(f"[RECEIVED from {addr[0]}] {msg}")

def send(message):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.sendto(message.encode('utf-8'), (MULTICAST_GROUP, PORT))
    sock.close()
    print(f"[SENT] {message}")

# Start listener thread
t = threading.Thread(target=listen, daemon=True)
t.start()

# Send a test message from "device 2"
time.sleep(1)
send("Hyg1 — Pt. Ready — Op3|GROUP")
time.sleep(1)
send("Rcp1 — Phone — LN2|ALL")

# Keep listening
print("\nListening for messages from emulator... (press Ctrl+C to stop)")
print("Type a message to send (format: 'Staff — Action — Location|TARGET'):")
try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print("\nDone")
