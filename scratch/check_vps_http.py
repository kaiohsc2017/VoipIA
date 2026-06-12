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
    stdin, stdout, stderr = ssh.exec_command("sudo ss -tulnp | grep -E ':80\b|:443\b'")
    out = stdout.read().decode()
    print("Listening on 80/443:")
    print(out)
    
    stdin, stdout, stderr = ssh.exec_command("docker ps | grep -E 'nginx|caddy'")
    out = stdout.read().decode()
    print("\nDocker containers (nginx/caddy):")
    print(out)
    
finally:
    ssh.close()
