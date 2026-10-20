package com.gustler.devtools;

/** SAL-158: 기존 초기 데이터는 유지하고, 수집 후 재시작과 별도 dev 후보 번들만 허용한 전환. */
final class CompatiblePreparation {
    static final String PREVIOUS = "1760d3f777e91af09ca577ebabb6faa0ab577f79aef92ef2bb8be27750f3fa56";
    static final String COLLECTED_DATA = "52b4d7117c1db439b8e90468eb334fc063f20a12387d4a72495ed936a45a508d";

    // SAL-222: 명시적 초기화 대상에 최소 평가 테이블을 포함한다. 기존 데이터와 번들은 유지한다.
    static final String NEXT = "37b4cace323ff5054a45266d740b05ba938b8c62aaf995c011256c710c190dc3";

    static boolean permits(RevisionStore.Revision previous, RevisionStore.Revision next) {
        var from = previous.files().get("tools/data-contract.sha256");
        var to = next.files().get("tools/data-contract.sha256");
        return PREVIOUS.equals(from) && (COLLECTED_DATA.equals(to) || NEXT.equals(to))
            || COLLECTED_DATA.equals(from) && NEXT.equals(to);
    }
}
