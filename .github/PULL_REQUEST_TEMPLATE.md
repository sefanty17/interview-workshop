## 改了什么

<!-- 一句话概括。例：修复情绪解析漏掉「冷淡」别名 -->

## 涉及文件

-

## 验证

<!-- 必填。写清跑了什么命令、看到什么结果，不要只写「已测试」 -->

- [ ] `cd frontend && npx vue-tsc --noEmit` → 0 error
- [ ] `cd backend && bash ../scripts/mvn.sh test` → 全绿

## 检查

- [ ] 没有提交密钥、`node_modules/`、`backend/target/`、`references/`
- [ ] 若改了 SSE 事件名或跨端字段，已在描述里单独说明
