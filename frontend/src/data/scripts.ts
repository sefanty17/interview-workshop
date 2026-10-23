// 由 scripts/gen_scripts_ts.py 从 data/seed/scripts.json 生成，不要手改。
// 结构与后端 interview_script / interview_chapter 两张表一一对应，
// 事件类型照搬 LingChat 剧本引擎（story_config.yaml + Chapters/*.yaml）。

export type EventType =
  | "ai_dialogue"
  | "input"
  | "narration"
  | "free_dialogue"
  | "modify_character"
  | "chapter_end";

export type EndType = "linear" | "branching" | "ai_judged";

export interface ScriptEvent {
  type: EventType;
  /** ai_dialogue / modify_character：角色代号，主面试官固定 MAIN */
  character?: string;
  /** ai_dialogue：给 LLM 的提示词 */
  prompt?: string;
  /** input：输入框占位提示（本项目扩展字段） */
  hint?: string;
  /** narration：旁白文本 */
  text?: string;
  /** modify_character：show_character / hide_character */
  action?: string;
  /** modify_character / background：时长（秒） */
  duration?: number;
  /** chapter_end：跳转方式 */
  end_type?: EndType;
  /** chapter_end + linear：下一章 code */
  next_chapter?: string;
  /** chapter_end + branching/ai_judged：候选分支，顺序即优先级。
   *  branching 认 condition / next / default；ai_judged 认 name / next / default。
   *  next 缺失时引擎会回落到「剧本结束」，所以编辑时必须能填。 */
  options?: { name?: string; next?: string; condition?: string; default?: boolean }[];
}

export interface InterviewChapter {
  code: string;
  name: string;
  order_num: number;
  events: ScriptEvent[];
}

export interface ScriptSettings {
  total_questions?: number;
  time_limit?: number;
  follow_up_depth?: number;
}

export interface InterviewScript {
  code: string;
  script_name: string;
  intro_chapter: string;
  description: string;
  recommand_start?: string;
  interviewer_code?: string;
  order_num: number;
  job_code?: string;
  level?: string;
  company_style?: string;
  script_settings?: ScriptSettings;
  chapters: InterviewChapter[];
}

export const seedScripts: InterviewScript[] = [
  {
    "code": "java-strict",
    "script_name": "Java 后端 · 严格三轮",
    "intro_chapter": "intro",
    "description": "面向校招 Java 后端：基础扎实度、项目真实性、场景设计三层递进，答得含糊会被当场追问。",
    "recommand_start": "选择「严格技术面」面试官时",
    "interviewer_code": "strict",
    "order_num": 1,
    "job_code": "java",
    "level": "校招",
    "company_style": "一线互联网大厂",
    "script_settings": {
      "total_questions": 8,
      "time_limit": 40,
      "follow_up_depth": 3
    },
    "chapters": [
      {
        "code": "intro",
        "name": "破冰与自我介绍",
        "order_num": 1,
        "events": [
          {
            "type": "narration",
            "text": "面试开始。录音与计时已开启。"
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "以面试官身份开场，请候选人做一分钟自我介绍。三句以内，不要寒暄客套。"
          },
          {
            "type": "input",
            "hint": "一分钟自我介绍"
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "从自我介绍里挑一个项目，追问一个具体细节：他在其中具体负责哪部分、遇到的最大问题是什么。"
          },
          {
            "type": "input",
            "hint": "项目里你负责的部分"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "basic"
          }
        ]
      },
      {
        "code": "basic",
        "name": "基础技术",
        "order_num": 2,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问 JVM 内存分区：堆、栈、方法区分别放什么，什么情况下会 OOM。要求说清依据，不接受背诵式回答。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "针对上一题回答里含糊的点追问一次。若他答得完整，改问 HashMap 扩容机制与并发场景下的问题。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问 Spring 事务在哪些情况下会失效，至少举两个场景并说明原理。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "ai_judged",
            "prompt": "评估候选人在基础题阶段的表现。若 3 题里至少 2 题说出了关键点，选 deep 进入项目深挖；若明显吃力，选 basic_more 再补一道基础题。",
            "options": [
              {
                "name": "deep",
                "next": "deep"
              },
              {
                "name": "basic_more",
                "next": "basic_more"
              }
            ]
          }
        ]
      },
      {
        "code": "basic_more",
        "name": "基础补充",
        "order_num": 3,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "补一道基础题：MySQL 索引为什么用 B+ 树，而不是红黑树或哈希表。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "deep"
          }
        ]
      },
      {
        "code": "deep",
        "name": "项目深挖",
        "order_num": 4,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "挑候选人简历里最有分量的项目，追问架构：为什么这么分层、数据量涨十倍后哪里会先垮。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "针对上一题的技术选型，追问替代方案与代价。连续追问不超过 3 次，第 3 次后必须换角度。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "scenario"
          }
        ]
      },
      {
        "code": "scenario",
        "name": "场景设计",
        "order_num": 5,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "场景设计题：设计支持十万并发的秒杀下单接口，讲清限流、库存扣减、超卖防护。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "close"
          }
        ]
      },
      {
        "code": "close",
        "name": "反问与收尾",
        "order_num": 6,
        "events": [
          {
            "type": "narration",
            "text": "面试进入收尾阶段。"
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "请候选人提问。回答完后给出两条针对本场表现的具体建议，不要说套话，然后结束面试。"
          },
          {
            "type": "input",
            "hint": "你有什么想问的？"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "end"
          }
        ]
      }
    ]
  },
  {
    "code": "frontend-standard",
    "script_name": "Web 前端 · 常规流程",
    "intro_chapter": "intro",
    "description": "面向校招前端：从项目切入基础与框架原理，节奏平缓，允许候选人充分表达。",
    "recommand_start": "选择「温和 HR 面」面试官时",
    "interviewer_code": "friendly",
    "order_num": 2,
    "job_code": "frontend",
    "level": "校招",
    "company_style": "成长型互联网公司",
    "script_settings": {
      "total_questions": 6,
      "time_limit": 30,
      "follow_up_depth": 2
    },
    "chapters": [
      {
        "code": "intro",
        "name": "自我介绍与项目概览",
        "order_num": 1,
        "events": [
          {
            "type": "narration",
            "text": "面试开始，放松一点，当作聊天。"
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "用轻松的语气请候选人做自我介绍，并说说最近做得最有成就感的一个项目。"
          },
          {
            "type": "input",
            "hint": "自我介绍 + 一个项目"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "project"
          }
        ]
      },
      {
        "code": "project",
        "name": "项目经历",
        "order_num": 2,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "就候选人说的项目，追问三个点：技术栈选型、他遇到的性能瓶颈、最后怎么解决的。一次只问一个。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "根据上一题的回答继续追问一层，最多追问两次；他答不上来就给个方向提示，不要打断。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "basic"
          }
        ]
      },
      {
        "code": "basic",
        "name": "技术基础",
        "order_num": 3,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问：事件循环里宏任务与微任务的执行顺序，举一段代码说清输出。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问 Vue 3 响应式原理：Proxy 相比 Object.defineProperty 解决了什么问题。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "ai_judged",
            "prompt": "评估候选人的基础掌握程度。若两题都能说出原理，选 perf 进入性能优化；否则选 close 直接收尾。",
            "options": [
              {
                "name": "perf",
                "next": "perf"
              },
              {
                "name": "close",
                "next": "close"
              }
            ]
          }
        ]
      },
      {
        "code": "perf",
        "name": "性能优化",
        "order_num": 4,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问：一个首屏 5 秒的页面，你会按什么顺序排查和优化？要求给出可执行的步骤。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "close"
          }
        ]
      },
      {
        "code": "close",
        "name": "反问与收尾",
        "order_num": 5,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "请候选人提问，回答后给两条具体建议，然后结束。"
          },
          {
            "type": "input",
            "hint": "你有什么想问的？"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "end"
          }
        ]
      }
    ]
  },
  {
    "code": "python-pressure",
    "script_name": "Python 算法 · 压力面",
    "intro_chapter": "intro",
    "description": "面向校招算法岗：节奏快、追问密，考察抗压与思维严密性，卡壳只给一次提示。",
    "recommand_start": "选择「压力面」面试官时",
    "interviewer_code": "pressure",
    "order_num": 3,
    "job_code": "python",
    "level": "校招",
    "company_style": "高频面试的头部大厂",
    "script_settings": {
      "total_questions": 7,
      "time_limit": 25,
      "follow_up_depth": 5
    },
    "chapters": [
      {
        "code": "intro",
        "name": "快速破冰",
        "order_num": 1,
        "events": [
          {
            "type": "narration",
            "text": "面试开始，节奏会很快。"
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "简短开场，直接问：用三句话说明你最擅长的一个技术方向，不要铺垫。"
          },
          {
            "type": "input",
            "hint": "三句话"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "algo"
          }
        ]
      },
      {
        "code": "algo",
        "name": "算法与复杂度",
        "order_num": 2,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "出题：给一个无序数组找出第 K 大的数，先说思路再说复杂度，最后说优化空间。每问一步都要等他答完再问下一步。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "针对他的方案连续追问：时间复杂度还能不能降、空间换时间是否值得、边界条件怎么处理。每次只问一个。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "python"
          }
        ]
      },
      {
        "code": "python",
        "name": "Python 语言特性",
        "order_num": 3,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问：GIL 对多线程的影响，什么场景下多进程比多线程更合适。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "追问：装饰器与闭包的区别，写一个带参数的装饰器要考虑什么。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "ai_judged",
            "prompt": "评估候选人。若两题都答到关键点，选 deep 加压；若有一题完全卡住，选 close 收尾。",
            "options": [
              {
                "name": "deep",
                "next": "deep"
              },
              {
                "name": "close",
                "next": "close"
              }
            ]
          }
        ]
      },
      {
        "code": "deep",
        "name": "深度追问",
        "order_num": 4,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "追问：如果一个 Python 服务 CPU 打满，你怎么定位？给出完整的排查链路。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "close"
          }
        ]
      },
      {
        "code": "close",
        "name": "收尾",
        "order_num": 5,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "不给缓冲，直接问：本场你觉得自己哪道题答得最差，为什么。然后给一条建议并结束。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "end"
          }
        ]
      }
    ]
  },
  {
    "code": "java-expert",
    "script_name": "Java 后端 · 技术专家面",
    "intro_chapter": "intro",
    "description": "面向校招但按专家标准：只问原理与权衡，不讲结论讲依据，考察技术判断力。",
    "recommand_start": "选择「技术专家面」面试官时",
    "interviewer_code": "expert",
    "order_num": 4,
    "job_code": "java",
    "level": "校招（专家标准）",
    "company_style": "技术驱动型团队",
    "script_settings": {
      "total_questions": 7,
      "time_limit": 45,
      "follow_up_depth": 4
    },
    "chapters": [
      {
        "code": "intro",
        "name": "背景与方向",
        "order_num": 1,
        "events": [
          {
            "type": "narration",
            "text": "面试开始。我会从基础到系统设计逐层提问。"
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "请候选人说明自己的技术方向与最熟悉的一块，并给一个能证明的例子。"
          },
          {
            "type": "input",
            "hint": "技术方向 + 例子"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "principle"
          }
        ]
      },
      {
        "code": "principle",
        "name": "原理层",
        "order_num": 2,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问：synchronized 与 ReentrantLock 在实现层面的差别，各自适用什么场景。要求讲清 AQS 的作用。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "追问：从偏向锁到重量级锁的升级过程，为什么要设计这套升级机制。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "design"
          }
        ]
      },
      {
        "code": "design",
        "name": "系统设计",
        "order_num": 3,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "设计题：一个日均千万级请求的短链服务，讲清存储选型、缓存策略、ID 生成方案与容量估算。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "针对他的设计挑一个薄弱环节追问：如果缓存全部失效会发生什么，你怎么防。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "tradeoff"
          }
        ]
      },
      {
        "code": "tradeoff",
        "name": "权衡与取舍",
        "order_num": 4,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "提问：让你在强一致与高可用之间做选择，你会怎么向业务方解释这个取舍？给出判断依据。"
          },
          {
            "type": "input",
            "hint": ""
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "close"
          }
        ]
      },
      {
        "code": "close",
        "name": "收尾与建议",
        "order_num": 5,
        "events": [
          {
            "type": "ai_dialogue",
            "character": "MAIN",
            "prompt": "请候选人提问。之后给两条具体、可执行的改进建议，说明依据，然后结束。"
          },
          {
            "type": "input",
            "hint": "你有什么想问的？"
          },
          {
            "type": "chapter_end",
            "end_type": "linear",
            "next_chapter": "end"
          }
        ]
      }
    ]
  }
];

export function findScript(code: string): InterviewScript | undefined {
  return seedScripts.find((s) => s.code === code);
}

/** 章节跳转表的唯一来源：每章最后那条 chapter_end */
export function nextChapterOf(ch: InterviewChapter): string | undefined {
  const end = ch.events.find((e) => e.type === "chapter_end");
  if (!end) return undefined;
  if (end.end_type === "linear") return end.next_chapter;
  return undefined;
}
