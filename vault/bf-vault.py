#!/usr/bin/env python3
"""BeyondFlow: Geheimnisse in OCI Vault (Secret Management).

Anmeldung als VM (Instance Principal, dynamische Gruppe) - es liegt kein Zugangsschluessel auf der Platte.
Werte werden NIE ausgegeben, nur Namen und Anzahlen.

  bf-vault.py fetch   --vault OCID --out DATEI [--prefix BF_] [--strip PREFIX]
        alle aktiven Secrets mit dem Praefix lesen und als KEY=WERT nach DATEI schreiben (Rechte 600, atomar)
  bf-vault.py migrate --vault OCID --key OCID --env DATEI [--name-prefix SAPGW_] [--only K1,K2]
        KEY=WERT-Zeilen aus DATEI als Secrets anlegen bzw. als neue Version setzen
  bf-vault.py check   --vault OCID [--prefix BF_]
        Lesezugriff pruefen, Namen der Secrets auflisten
Compartment: --compartment, sonst das der VM (Instance Metadata).
"""
import argparse, base64, json, os, sys, tempfile, urllib.request

try:
    import oci
except ImportError:
    sys.exit("Python-Paket 'oci' fehlt (sudo dnf install -y python39-oci-sdk oder python3 -m pip install --user oci)")


def imds_compartment():
    req = urllib.request.Request("http://169.254.169.254/opc/v2/instance/", headers={"Authorization": "Bearer Oracle"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.load(r)["compartmentId"]


def clients():
    signer = oci.auth.signers.InstancePrincipalsSecurityTokenSigner()
    cfg = {"region": signer.region}
    return oci.vault.VaultsClient(cfg, signer=signer), oci.secrets.SecretsClient(cfg, signer=signer)


def list_secrets(vaults, comp, vault):
    return oci.pagination.list_call_get_all_results(vaults.list_secrets, compartment_id=comp, vault_id=vault, lifecycle_state="ACTIVE").data


def read_env(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.lstrip().startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k.strip() and v != "":
                out[k.strip()] = v
    return out


def cmd_fetch(a, vaults, secrets, comp):
    env = {}
    for s in list_secrets(vaults, comp, a.vault):
        if a.prefix and not s.secret_name.startswith(a.prefix):
            continue
        content = secrets.get_secret_bundle(secret_id=s.id, stage="CURRENT").data.secret_bundle_content.content
        value = base64.b64decode(content).decode("utf-8")
        if "\n" in value:
            sys.exit(f"Secret {s.secret_name} enthaelt einen Zeilenumbruch - nicht verwendbar")
        name = s.secret_name[len(a.strip):] if a.strip and s.secret_name.startswith(a.strip) else s.secret_name
        env[name] = value
    os.makedirs(os.path.dirname(a.out), mode=0o700, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(a.out), prefix=".secrets-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        for k in sorted(env):
            f.write(f"{k}={env[k]}\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, a.out)
    print(f"{len(env)} Geheimnisse aus OCI Vault geladen: {', '.join(sorted(env))}")


def cmd_migrate(a, vaults, secrets, comp):
    if not a.key:
        sys.exit("--key (Schluessel-OCID) fehlt")
    src = read_env(a.env)
    if a.only:
        src = {k: v for k, v in src.items() if k in a.only.split(",")}
    existing = {s.secret_name: s for s in list_secrets(vaults, comp, a.vault)}
    created = updated = same = 0
    for k, v in sorted(src.items()):
        name = a.name_prefix + k
        b64 = base64.b64encode(v.encode("utf-8")).decode()
        content = oci.vault.models.Base64SecretContentDetails(content_type="BASE64", content=b64, stage="CURRENT")
        if name in existing:
            cur = secrets.get_secret_bundle(secret_id=existing[name].id, stage="CURRENT").data.secret_bundle_content.content
            if cur == b64:
                same += 1
                continue
            vaults.update_secret(existing[name].id, oci.vault.models.UpdateSecretDetails(secret_content=content))
            updated += 1
            print(f"  neue Version: {name}")
        else:
            vaults.create_secret(oci.vault.models.CreateSecretDetails(
                compartment_id=comp, vault_id=a.vault, key_id=a.key, secret_name=name, secret_content=content,
                description="BeyondFlow - angelegt von bf-vault.py migrate"))
            created += 1
            print(f"  angelegt:     {name}")
    print(f"Uebertragen: {created} neu, {updated} aktualisiert, {same} unveraendert (Werte werden nicht angezeigt)")


def cmd_check(a, vaults, secrets, comp):
    names = sorted(s.secret_name for s in list_secrets(vaults, comp, a.vault) if not a.prefix or s.secret_name.startswith(a.prefix))
    print(f"Lesezugriff OK, {len(names)} Secrets: {', '.join(names) or '-'}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("cmd", choices=["fetch", "migrate", "check"])
    p.add_argument("--vault", required=True)
    p.add_argument("--key")
    p.add_argument("--compartment")
    p.add_argument("--out")
    p.add_argument("--env")
    p.add_argument("--prefix", default="")
    p.add_argument("--strip", default="")
    p.add_argument("--name-prefix", default="")
    p.add_argument("--only", default="")
    a = p.parse_args()
    try:
        comp = a.compartment or imds_compartment()
        vaults, secrets = clients()
        {"fetch": cmd_fetch, "migrate": cmd_migrate, "check": cmd_check}[a.cmd](a, vaults, secrets, comp)
    except oci.exceptions.ServiceError as e:
        hint = " - Richtlinie/dynamische Gruppe pruefen" if e.status in (401, 403, 404) else ""
        sys.exit(f"OCI Vault: HTTP {e.status} {e.code}{hint}")


if __name__ == "__main__":
    main()
