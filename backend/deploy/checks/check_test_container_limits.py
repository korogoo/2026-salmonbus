#!/usr/bin/env python3
"""Java 테스트의 알려진 Docker CPU/메모리 제한 호출을 검사한다.

CodeBuild에서 cgroup 설정 오류를 일으킨 설정의 재유입 방지용이다.
타입 분석은 하지 않으며 리플렉션, 별도 래퍼, 외부 설정까지 검증하지 않는다.
"""
import re
import sys
from pathlib import Path

IGNORED = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
LIMIT_CALL = re.compile(
    r'\b(withNanoCPUs|withCpuCount|withCpuPercent|withCpuPeriod|withCpuQuota|'
    r'withCpuShares|withCpusetCpus|withCpusetMems|withMemory|withMemorySwap|'
    r'withMemoryReservation|withMemorySwappiness)\s*\('
)


def violations(source):
    code = IGNORED.sub(lambda match: re.sub(r'[^\n]', ' ', match.group()), source)
    return [(code.count('\n', 0, match.start()) + 1, match.group(1))
            for match in LIMIT_CALL.finditer(code)]


def main(root):
    found = []
    for path in sorted(root.rglob('*.java')):
        relative = path.relative_to(root).as_posix()
        if not re.search(r'(^|/)src/[^/]*[Tt]est[^/]*/', relative):
            continue
        for line, method in violations(path.read_text(encoding='utf-8')):
            found.append(f'{relative}:{line}: {method}')
    if found:
        print('테스트 컨테이너의 CPU/메모리 제한 설정을 발견했습니다.', file=sys.stderr)
        print('\n'.join(found), file=sys.stderr)
        print('CodeBuild에서 cgroup 설정 오류로 DB 시작이 실패한 전력이 있습니다. '
              '제한 설정을 제거하거나 배포 빌드 환경 호환성을 먼저 검토하세요.', file=sys.stderr)
        return 1
    print('테스트 컨테이너 자원 제한 검사 통과')
    return 0


if __name__ == '__main__':
    sys.exit(main(Path(__file__).resolve().parents[2]))
