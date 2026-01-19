from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target

# list all enum names and values
models = [m for m in dir(qnn_target.SocModel) if not m.startswith("_")]
for m in models:
    print(m)
# Optional: filter for 'canoe' or '660'
matches = [m for m in models if "canoe" in m.lower() or "660" in m.lower() or "gen" in m.lower()]
print("candidates:", matches)