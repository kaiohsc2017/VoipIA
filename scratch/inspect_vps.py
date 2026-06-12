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
    stdin, stdout, stderr = ssh.exec_command("docker ps")
    print(stdout.read().decode())
    
    stdin, stdout, stderr = ssh.exec_command("docker inspect caddy-proxy")
    print(stdout.read().decode()[:1500])
finally:
    ssh.close()
