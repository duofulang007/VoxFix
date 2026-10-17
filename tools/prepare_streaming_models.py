"""Pre-optimize the pinned streaming graphs on the build machine, not at microphone startup."""
import hashlib
import json
from pathlib import Path

import onnxruntime as ort
from setup_voice import FILES

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.android/streaming-optimized'
VERSION = '1.31.0'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    if ort.__version__ != VERSION:
        raise RuntimeError('Build requires .venv onnxruntime==' + VERSION + '; install tools/streaming-optimization-requirements.txt')
    sources = {}
    for destination, expected in FILES['sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile'].values():
        if not destination.endswith('.onnx'):
            continue
        source = ROOT / 'models' / destination
        if digest(source) != expected:
            raise ValueError('Streaming source checksum mismatch: ' + destination)
        sources[source.name] = expected
    config = {'onnxruntime': VERSION, 'optimization': 'EXTENDED_CPU_NO_LAYOUT',
              'scriptSha256': digest(Path(__file__)), 'sources': sources}
    receipt = OUT / 'receipt.json'
    if receipt.is_file():
        saved = json.loads(receipt.read_text(encoding='utf-8'))
        if saved.get('config') == config and all((OUT / name).is_file() and digest(OUT / name) == value
                for name, value in saved.get('outputs', {}).items()) and saved.get('outputs', {}).keys() == sources.keys():
            print('STREAMING_GRAPHS_VERIFIED')
            return
    OUT.mkdir(parents=True, exist_ok=True)
    outputs = {}
    for name in sources:
        scratch = OUT / (name + '.part.onnx')
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        # Layout optimizations can embed desktop CPU assumptions; ARM64 uses only these graph fusions.
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
        options.optimized_model_filepath = str(scratch)
        original = ort.InferenceSession(str(ROOT / 'models/streaming' / name), options, providers=['CPUExecutionProvider'])
        options.optimized_model_filepath = ''
        optimized = ort.InferenceSession(str(scratch), options, providers=['CPUExecutionProvider'])
        assert original.get_modelmeta().custom_metadata_map == optimized.get_modelmeta().custom_metadata_map, name
        for before, after in [(original.get_inputs(), optimized.get_inputs()), (original.get_outputs(), optimized.get_outputs())]:
            assert [(v.name, v.type, v.shape) for v in before] == [(v.name, v.type, v.shape) for v in after], name
        del original, optimized
        scratch.replace(OUT / name)
        outputs[name] = digest(OUT / name)
    temporary = receipt.with_suffix('.tmp')
    temporary.write_text(json.dumps({'config': config, 'outputs': outputs}, indent=2) + '\n', encoding='utf-8')
    temporary.replace(receipt)
    print('STREAMING_GRAPHS_READY')


if __name__ == '__main__':
    main()
