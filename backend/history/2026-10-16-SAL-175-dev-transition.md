# SAL-175 dev 최초 전환

기존 dev RDS 데이터를 유지한다. CodePipeline의 API → worker 순서를 바꾸지 않는다.
V38 전용 복구 패키지는 만들지 않는다. 이전 배포 파일과 배포 전 DB의 복구 가능 여부는
운영자가 먼저 확인해야 한다. 아래 도구는 백업을 만들거나 복구 가능성을 검증하지 않는다.

## 배포 전에 한 번 승인

대상은 공유 dev EC2뿐이다. 새 API 배포 번들을 보호된 디렉터리에 내려받고, 그 번들의
`basis/tools/shared-dev-tools.jar`로 다음 명령을 root 권한으로 실행한다.

```text
java -jar <새 API 번들>/basis/tools/shared-dev-tools.jar approve-data-transition \
  --bundle <새 API 번들> --backup-reference <확인한 배포 전 DB 백업 식별자>
```

이 명령은 DB를 수정하거나 앱을 중지하지 않는다. 현재 basis와 새 basis의 정확한
release ID, 백업 식별자, 30분의 유효기간을
`/srv/salmonbus-dev/deployment-preparation/approval.json`에 root 전용으로 남긴다.
승인한 번들과 실제 배포 번들이 달라지면 다시 승인해야 한다.

API BeforeInstall은 두 앱을 중지하고 데이터 계약 변경을 적용한다. 계약 파일 변경만
허용하며 노선·모델 파일과 MongoDB·인프라 데이터 설정의 변경은 기존처럼 거절한다.
새 설정으로도 dev DB 대상이 같은지 확인하고, 기존 대기 테이블을 사용하는 DB에서만
`prepare-evaluation-work-transition.sql`을 실행한다. worker·정비 DB 연결이 남아 있으면
중단한다. 이 SQL은 백업 식별자와 30분짜리 V39 승인 기록만 만들며 데이터를 초기화하지 않는다.
API의 Flyway가 구조를 변경하고 API 검증이 끝나야 새 worker를 시작할 수 있다.
DB 승인 전에 두 앱의 `40-data-transition.conf` drop-in을 설치한다. 새 도구의 고정된
basis release 경로로 기동을 검증하므로 이전 CodeDeploy 번들로 돌아가도 차단이 유지된다.
API와 worker의 검증이 모두 끝나면 이 drop-in만 제거한다.

## 실패하면 자동으로 이전 앱을 켜지 않음

승인된 데이터 계약 전환이 시작된 뒤에는 실패 지점에 상관없이 보수적으로
`RECOVERY_REQUIRED`를 남긴다. 두 앱을 중지하며 이전 basis·앱으로 자동 복귀하지 않는다.
DB 변경 여부를 확인할 수 없는데 이전 worker를 시작하는 것을 막기 위한 처리다.

복구 시에는 자동 배포·정비 작업을 멈추고, 배포 전 DB 및 그와 호환되는 기존
basis/API/worker를 운영자가 함께 복구해야 한다. 설정 복구만으로 DB가 되돌아가지는 않는다.
확인 없이 `current.json`을 삭제하거나 상태를 `COMPLETE`로 바꿔 재시작하지 않는다.
복구 후의 재개는 별도 운영 확인 절차이며 이번 도구가 자동 실행하지 않는다.

일반 배포에서 데이터 계약이 바뀌지 않는 경우는 기존 자동 복구 동작을 유지한다.
운영용 `evaluation-work-transition.sh prepare`도 백업 식별자 하나만 받도록 변경한다.
운영용 `resume-legacy`의 DB·JAR 버전 일치 검사는 유지한다.
V39 전용 롤백 SQL은 V38까지만 되돌리므로, 직전 운영 DB 전체 복구와 혼동하지 않는다.

## 로컬 검증 (2026-10-16)

- `backend/gradlew -p dev/server/tools test jar testHarnessJar`: 일반·PostgreSQL 테스트 53개 통과.
- Linux JRE 21 컨테이너의 `shared-dev-tools-tests.jar`: 60개 통과. CPU 1개, 메모리 384MiB로 제한했다.
- `bash backend/deploy/rehearsal/run.sh`: 모의 배포 166개, 전환 차단 23개 통과.
- `bash dev/tests/cli-contract.sh`, `bash dev/tests/image-lifecycle.sh`: 통과.
- 기존 출력 읽기 제한시간 테스트는 Java의 부모 종료·스트림 닫기 순서에 따라 정상 반환도 가능했다.
  정상 반환 또는 명시적 시간초과를 허용하되, 두 경우 모두 전체 소요시간 제한을 검사한다.
- 로컬 Compose 플러그인이 없어 `dev/local.sh check`와 Compose 통합 검증은 완료하지 못했다.
  사용자가 승인한 대로 해당 범위는 푸시 후 원격 CI에서 확인한다.
- 백엔드 기능 코드는 직전 검증 커밋 `0ef3e204`와 동일하다. 기존 전체 빌드 결과를 재사용하고
  이번 변경에 해당하는 배포 도구와 스크립트를 다시 검증했다.
- 실제 dev 배포, AWS 백업 존재·복구 가능성, 실제 systemd 기동은 이번 로컬 검증 범위가 아니다.
- 구현·검증 단계 토큰 사용량: 미측정. 단계별 턴 사용량을 연결할 수 있는 기록이 없다.
