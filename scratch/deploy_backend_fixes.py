import paramiko
import sys

HOST = "app.voiphash.com.br"
PORT = 22022
USER = "root"
PASS = "Nic@Mic@07"

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    ssh.connect(HOST, port=PORT, username=USER, password=PASS, timeout=10)
    
    print("1. Git pull...")
    stdin, stdout, stderr = ssh.exec_command("cd /opt/AsteriskIA && git pull origin main")
    print(stdout.read().decode())
    
    print("2. Rebuild backend and ai-agent...")
    stdin, stdout, stderr = ssh.exec_command("cd /opt/AsteriskIA && docker compose build backend ai-agent && docker compose up -d backend ai-agent")
    print(stdout.read().decode())
    print(stderr.read().decode())
    
finally:
    ssh.close()
