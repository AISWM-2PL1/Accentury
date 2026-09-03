-- 테스트 전용 발행본 - 음성 문장 풀 픽스처 두 벌 (KAN-182).
--
-- test 프로파일에서만 적용된다 (application-test.yml의 spring.flyway.locations). 운영 스키마에는
-- 들어가지 않는다. 세트 분할 규칙(VoiceSets)이 실제 DB 발행 경로(마이그레이션 -> 기동 검증 ->
-- 세트 유도)에서 도는 것을 검증하는 픽스처다 - 단위 테스트는 규칙을, 이 픽스처는 경로를 본다.
--
--   * gn-2026.09.t7  - N = 7, 채움이 일어나는 풀. 세트 2 = poolIndex 6, 7 + 1, 2, 3.
--                      전 음성 문항에 scriptKey("1|n")가 있어 AI로 가는 meta에 실린다 (§4.1).
--   * gn-2026.09.t10 - N = 10, 5의 배수라 채움이 없는 풀. 세트 2 = poolIndex 6..10.
--                      scriptKey가 없다 - meta에서 필드가 생략되는 쪽이다.
--
-- guideF0는 3프레임짜리 최소 곡선이다 - 길이 규칙(values, bandLow, bandHigh 동일 길이)만 지킨다.
-- published_at은 baseline(2026-08-09)보다 뒤라 관리자 목록에서 마지막에 온다.

insert into test_definition (test_version, dialect, score_version, body, published_at)
values ('gn-2026.09.t7', 'GYEONGNAM', 'sv-0.3', $definition${
  "testVersion": "gn-2026.09.t7",
  "scoreVersion": "sv-0.3",
  "dialect": "GYEONGNAM",
  "estimatedDurationSec": 240,
  "items": [
    {
      "itemId": "v1",
      "seq": 1,
      "type": "VOICE",
      "prompt": "풀 문장 1",
      "scriptKey": "1|1",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.1,
          0.6,
          1.1
        ],
        "bandLow": [
          -1.4,
          -0.9,
          -0.4
        ],
        "bandHigh": [
          1.6,
          2.1,
          2.6
        ]
      }
    },
    {
      "itemId": "w1",
      "seq": 2,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 1",
      "choices": [
        {
          "choiceId": "w1a",
          "text": "부추"
        },
        {
          "choiceId": "w1b",
          "text": "미나리"
        },
        {
          "choiceId": "w1c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w1d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w1a"
    },
    {
      "itemId": "v2",
      "seq": 3,
      "type": "VOICE",
      "prompt": "풀 문장 2",
      "scriptKey": "1|2",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.2,
          0.7,
          1.2
        ],
        "bandLow": [
          -1.3,
          -0.8,
          -0.3
        ],
        "bandHigh": [
          1.7,
          2.2,
          2.7
        ]
      }
    },
    {
      "itemId": "w2",
      "seq": 4,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 2",
      "choices": [
        {
          "choiceId": "w2a",
          "text": "부추"
        },
        {
          "choiceId": "w2b",
          "text": "미나리"
        },
        {
          "choiceId": "w2c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w2d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w2a"
    },
    {
      "itemId": "v3",
      "seq": 5,
      "type": "VOICE",
      "prompt": "풀 문장 3",
      "scriptKey": "1|3",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.3,
          0.8,
          1.3
        ],
        "bandLow": [
          -1.2,
          -0.7,
          -0.2
        ],
        "bandHigh": [
          1.8,
          2.3,
          2.8
        ]
      }
    },
    {
      "itemId": "w3",
      "seq": 6,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 3",
      "choices": [
        {
          "choiceId": "w3a",
          "text": "부추"
        },
        {
          "choiceId": "w3b",
          "text": "미나리"
        },
        {
          "choiceId": "w3c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w3d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w3a"
    },
    {
      "itemId": "v4",
      "seq": 7,
      "type": "VOICE",
      "prompt": "풀 문장 4",
      "scriptKey": "1|4",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.4,
          0.9,
          1.4
        ],
        "bandLow": [
          -1.1,
          -0.6,
          -0.1
        ],
        "bandHigh": [
          1.9,
          2.4,
          2.9
        ]
      }
    },
    {
      "itemId": "w4",
      "seq": 8,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 4",
      "choices": [
        {
          "choiceId": "w4a",
          "text": "부추"
        },
        {
          "choiceId": "w4b",
          "text": "미나리"
        },
        {
          "choiceId": "w4c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w4d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w4a"
    },
    {
      "itemId": "v5",
      "seq": 9,
      "type": "VOICE",
      "prompt": "풀 문장 5",
      "scriptKey": "1|5",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.5,
          1.0,
          1.5
        ],
        "bandLow": [
          -1.0,
          -0.5,
          0.0
        ],
        "bandHigh": [
          2.0,
          2.5,
          3.0
        ]
      }
    },
    {
      "itemId": "w5",
      "seq": 10,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 5",
      "choices": [
        {
          "choiceId": "w5a",
          "text": "부추"
        },
        {
          "choiceId": "w5b",
          "text": "미나리"
        },
        {
          "choiceId": "w5c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w5d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w5a"
    },
    {
      "itemId": "v6",
      "seq": 11,
      "type": "VOICE",
      "prompt": "풀 문장 6",
      "scriptKey": "1|6",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.6,
          1.1,
          1.6
        ],
        "bandLow": [
          -0.9,
          -0.4,
          0.1
        ],
        "bandHigh": [
          2.1,
          2.6,
          3.1
        ]
      }
    },
    {
      "itemId": "v7",
      "seq": 12,
      "type": "VOICE",
      "prompt": "풀 문장 7",
      "scriptKey": "1|7",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.7,
          1.2,
          1.7
        ],
        "bandLow": [
          -0.8,
          -0.3,
          0.2
        ],
        "bandHigh": [
          2.2,
          2.7,
          3.2
        ]
      }
    }
  ]
}$definition$, timestamp with time zone '2026-09-03T00:00:00Z');

insert into test_definition (test_version, dialect, score_version, body, published_at)
values ('gn-2026.09.t10', 'GYEONGNAM', 'sv-0.3', $definition${
  "testVersion": "gn-2026.09.t10",
  "scoreVersion": "sv-0.3",
  "dialect": "GYEONGNAM",
  "estimatedDurationSec": 240,
  "items": [
    {
      "itemId": "v1",
      "seq": 1,
      "type": "VOICE",
      "prompt": "풀 문장 1",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.1,
          0.6,
          1.1
        ],
        "bandLow": [
          -1.4,
          -0.9,
          -0.4
        ],
        "bandHigh": [
          1.6,
          2.1,
          2.6
        ]
      }
    },
    {
      "itemId": "w1",
      "seq": 2,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 1",
      "choices": [
        {
          "choiceId": "w1a",
          "text": "부추"
        },
        {
          "choiceId": "w1b",
          "text": "미나리"
        },
        {
          "choiceId": "w1c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w1d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w1a"
    },
    {
      "itemId": "v2",
      "seq": 3,
      "type": "VOICE",
      "prompt": "풀 문장 2",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.2,
          0.7,
          1.2
        ],
        "bandLow": [
          -1.3,
          -0.8,
          -0.3
        ],
        "bandHigh": [
          1.7,
          2.2,
          2.7
        ]
      }
    },
    {
      "itemId": "w2",
      "seq": 4,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 2",
      "choices": [
        {
          "choiceId": "w2a",
          "text": "부추"
        },
        {
          "choiceId": "w2b",
          "text": "미나리"
        },
        {
          "choiceId": "w2c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w2d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w2a"
    },
    {
      "itemId": "v3",
      "seq": 5,
      "type": "VOICE",
      "prompt": "풀 문장 3",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.3,
          0.8,
          1.3
        ],
        "bandLow": [
          -1.2,
          -0.7,
          -0.2
        ],
        "bandHigh": [
          1.8,
          2.3,
          2.8
        ]
      }
    },
    {
      "itemId": "w3",
      "seq": 6,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 3",
      "choices": [
        {
          "choiceId": "w3a",
          "text": "부추"
        },
        {
          "choiceId": "w3b",
          "text": "미나리"
        },
        {
          "choiceId": "w3c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w3d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w3a"
    },
    {
      "itemId": "v4",
      "seq": 7,
      "type": "VOICE",
      "prompt": "풀 문장 4",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.4,
          0.9,
          1.4
        ],
        "bandLow": [
          -1.1,
          -0.6,
          -0.1
        ],
        "bandHigh": [
          1.9,
          2.4,
          2.9
        ]
      }
    },
    {
      "itemId": "w4",
      "seq": 8,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 4",
      "choices": [
        {
          "choiceId": "w4a",
          "text": "부추"
        },
        {
          "choiceId": "w4b",
          "text": "미나리"
        },
        {
          "choiceId": "w4c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w4d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w4a"
    },
    {
      "itemId": "v5",
      "seq": 9,
      "type": "VOICE",
      "prompt": "풀 문장 5",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.5,
          1.0,
          1.5
        ],
        "bandLow": [
          -1.0,
          -0.5,
          0.0
        ],
        "bandHigh": [
          2.0,
          2.5,
          3.0
        ]
      }
    },
    {
      "itemId": "w5",
      "seq": 10,
      "type": "VOCABULARY",
      "prompt": "어휘 문항 5",
      "choices": [
        {
          "choiceId": "w5a",
          "text": "부추"
        },
        {
          "choiceId": "w5b",
          "text": "미나리"
        },
        {
          "choiceId": "w5c",
          "text": "쑥갓"
        },
        {
          "choiceId": "w5d",
          "text": "시금치"
        }
      ],
      "correctChoiceId": "w5a"
    },
    {
      "itemId": "v6",
      "seq": 11,
      "type": "VOICE",
      "prompt": "풀 문장 6",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.6,
          1.1,
          1.6
        ],
        "bandLow": [
          -0.9,
          -0.4,
          0.1
        ],
        "bandHigh": [
          2.1,
          2.6,
          3.1
        ]
      }
    },
    {
      "itemId": "v7",
      "seq": 12,
      "type": "VOICE",
      "prompt": "풀 문장 7",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.7,
          1.2,
          1.7
        ],
        "bandLow": [
          -0.8,
          -0.3,
          0.2
        ],
        "bandHigh": [
          2.2,
          2.7,
          3.2
        ]
      }
    },
    {
      "itemId": "v8",
      "seq": 13,
      "type": "VOICE",
      "prompt": "풀 문장 8",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.8,
          1.3,
          1.8
        ],
        "bandLow": [
          -0.7,
          -0.2,
          0.3
        ],
        "bandHigh": [
          2.3,
          2.8,
          3.3
        ]
      }
    },
    {
      "itemId": "v9",
      "seq": 14,
      "type": "VOICE",
      "prompt": "풀 문장 9",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          0.9,
          1.4,
          1.9
        ],
        "bandLow": [
          -0.6,
          -0.1,
          0.4
        ],
        "bandHigh": [
          2.4,
          2.9,
          3.4
        ]
      }
    },
    {
      "itemId": "v10",
      "seq": 15,
      "type": "VOICE",
      "prompt": "풀 문장 10",
      "guideF0": {
        "unit": "semitone",
        "frameIntervalMs": 10,
        "values": [
          1.0,
          1.5,
          2.0
        ],
        "bandLow": [
          -0.5,
          0.0,
          0.5
        ],
        "bandHigh": [
          2.5,
          3.0,
          3.5
        ]
      }
    }
  ]
}$definition$, timestamp with time zone '2026-09-03T00:00:01Z');
