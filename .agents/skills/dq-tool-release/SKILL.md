---
name: dq-tool-release
description: dq-tool 发布全流程 — 提交改动到 main、推送 GitHub、把指定 tag 指向最新提交并 push(含同名分支/tag 的 refspec 与 force-with-lease 安全移动)
version: 1.0.0
author: Auto-generated from session
metadata:
  hermes:
    tags: [release, git, tag, dq-tool, publish, github]
    related_skills: [git-commit]
---

# dq-tool 发布流程

## Trigger Conditions

- 用户说"发布"、"发版"、"打个 tag"、"把 X tag 指向最新提交并 push"、"提交到 main 并推 GitHub"等
- 用户要求把本地改动走完「提交 main → 推送 → 打 tag → 推 tag」全流程

## 项目背景(必须遵守)

- 仓库:dq-tool,路径 `/Volumes/code/com.codeup.aliyun/tools`;默认分支 `main`
- **版本号唯一源头是根目录 `VERSION` 文件**(如 `0.1.8`)。发布 tag 命名 = `v` + 去掉开头 `0.` 的版本号(`0.1.8` → `v1.8`,与 package-win.bat 的 PKG_VERSION、wiki 发版步骤口径一致);升版本号用 `scripts/bump-version.sh`,不要手改散落各处
- 提交规范(复用 git-commit skill):中文 message,格式 `<类型>(<scope>): <一句话>` + 变更内容/影响范围;类型 feat/fix/refactor/chore/docs/style/test
- 红线:只提交自己改的文件;**严禁 `git add -A` / `git add .`**
- 推 main 或推 `v*` tag 会触发 `.github/workflows/release.yml`(当前启用 Windows 免安装 zip;前端依赖用 pnpm,lock 唯一来源 `web/pnpm-lock.yaml`)
- 发布细节见 `docs/wiki/打包与发布.md`(版本号变更流程、已踩过的坑)

## 工作流

### 1. 前置检查

```bash
git status -s                 # 改动归属:只提交本次发布相关的文件
git diff HEAD --name-only     # 逐个确认是自己/本次发布的改动
git log --oneline -3 origin/main   # 了解远端 main 位置
cat VERSION                   # 确认版本号,推导目标 tag(如 0.1.8 → v1.8)
```

- 有不属于本次发布的改动 → 先报给用户,绝不代交
- 改动较多时用 `git diff HEAD -- <file>` 抽查,警惕 IDE 格式化污染
- 若用户只给了版本号没给 tag:按 `v` + 去 `0.` 前缀推导并先与用户确认

### 2. 提交代码到 main

- 当前分支就是 `main`:先 `git pull --ff-only origin main` 保持最新 → 精准 `git add <文件...>` → 提交
- 当前分支不是 `main`(如 `v1.8` 分支):
  - 单人小项目默认:`git checkout main && git pull --ff-only origin main && git merge <分支>` 把改动合入 main,冲突就地解决后再提交
  - 或推送分支后由用户在 GitHub 开 PR 合并(仓库已有 PR #1 先例);采用哪种先与用户确认
- 提交信息按项目规范写中文 body;提交后必须 `git show --stat HEAD` 向用户展示

### 3. 推送到 GitHub

```bash
git push origin main
```

- 确认输出 `main -> main` 且无 rejected;被拒则先 `git pull --rebase` 再推

### 4. 将指定 tag 指向最新提交并 push

tag 与分支可能同名(如 `v1.8` 同时是分支和 tag),**读写一律用完整 refspec**:

```bash
# a) 确认要指向的提交(main 最新,或用户指定的 commit)
git rev-parse main

# b) 查远端 tag 现状(区分"不存在"与"远端已有旧值")
git ls-remote origin refs/tags/<tag>

# c) 本地创建/移动 tag
git tag <tag> <commit>          # 不存在:直接创建
git tag -f <tag> <commit>       # 已存在:移动

# d) 推送
#    新 tag(普通推送):
git push origin refs/tags/<tag>:refs/tags/<tag>
#    移动已有 tag:先拿到远端旧值 <old>,用 force-with-lease 显式传旧值安全强推:
git push origin refs/tags/<tag>:refs/tags/<tag> --force-with-lease=refs/tags/<tag>:<old>

# e) 校验:远端 tag 必须等于目标 commit
git ls-remote origin refs/tags/<tag>
```

- **坑**:本地 tag 已被移动后再用不带参数的 `--force-with-lease` 会报 `stale info`——lease 默认取本地 refs/tags 值(已被改),与远端不符;必须先 `ls-remote` 拿到远端旧值,显式传 `--force-with-lease=refs/tags/<tag>:<old>`
- 远端 tag 值与预期不符时**停下询问用户**,不要 plain `--force` 硬推

### 5. 汇报

- 提交 hash + `git show --stat HEAD` 统计
- main 推送结果
- tag 新旧值对比 + 远端校验结果
- 提示:推送已触发 GitHub Actions release 构建

## Pitfalls

- 同名分支与 tag 混用裸名字会报 `refname 'v1.8' is ambiguous` → 一律用 `refs/tags/<tag>` / `refs/heads/<branch>` 完整引用
- 强移 tag 是破坏性操作:新 tag 用普通推送,移动 tag 用 force-with-lease 且显式传远端旧值
- 提交信息禁用英文单行、禁用 emoji;注释/文档保持中文
- 版本号只改 `VERSION`(经 bump-version.sh),tag 名与 VERSION 必须对应,禁止拍脑袋命名
- 发布前确认工作区没有未提交的"本次发布之外"的改动;tag 应指向 main 最新提交
