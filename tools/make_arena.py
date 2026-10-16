"""Writes the flat GameTest arena: stone floor, 32x8x32. Usage: python make_arena.py <out.nbt>"""
import gzip, struct, sys

def tag(t, name, payload):
    n = name.encode()
    return bytes([t]) + struct.pack('>H', len(n)) + n + payload

def string(s):
    b = s.encode(); return struct.pack('>H', len(b)) + b

def compound(items):  # items: list of (type, name, payload)
    return b''.join(tag(t, n, p) for t, n, p in items) + b'\x00'

def int_list(values):
    return bytes([3]) + struct.pack('>i', len(values)) + b''.join(struct.pack('>i', v) for v in values)

def compound_list(payloads):
    return bytes([10]) + struct.pack('>i', len(payloads)) + b''.join(payloads)

SIZE, HEIGHT = 32, 8
blocks = [compound([(9, 'pos', int_list([x, 0, z])), (3, 'state', struct.pack('>i', 0))])
          for x in range(SIZE) for z in range(SIZE)]
root = compound([
    (3, 'DataVersion', struct.pack('>i', 3465)),
    (9, 'size', int_list([SIZE, HEIGHT, SIZE])),
    (9, 'palette', compound_list([compound([(8, 'Name', string('minecraft:stone'))])])),
    (9, 'blocks', compound_list(blocks)),
    (9, 'entities', bytes([10]) + struct.pack('>i', 0)),
])
with gzip.open(sys.argv[1], 'wb') as out:
    out.write(bytes([10]) + struct.pack('>H', 0) + root)
