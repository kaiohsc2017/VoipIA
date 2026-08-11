"""migrate.py — aplica schema do banco uma única vez antes de subir os workers."""
import asyncio
from database import migrate_db

if __name__ == "__main__":
    asyncio.run(migrate_db())
    print("[migrate] Schema aplicado com sucesso.")
