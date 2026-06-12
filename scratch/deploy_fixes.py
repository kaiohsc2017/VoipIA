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
    
    print("2. Rebuild ai-agent and asterisk...")
    stdin, stdout, stderr = ssh.exec_command("cd /opt/AsteriskIA && docker compose build ai-agent asterisk && docker compose up -d ai-agent asterisk")
    print(stdout.read().decode())
    print(stderr.read().decode())
    
finally:
    ssh.close()
