#!/bin/bash

# 脚本：批量更新分支并执行合并操作
# 功能：
# 1. 第一步：删除并重新拉取所有项目的 TEST_NORMAL、TEST_HOTFIX、TEMP_NORMAL 分支
# 2. 第二步：先按分支维度遍历，再按项目处理，如果项目没有该分支则不处理
# 3. 将 DEV_NORMAL 合并到配置的分支，并 push 配置分支本身到远程
# 4. 将这些分支合并到对应的 TEST_NORMAL、TEST_HOTFIX、TEMP_NORMAL 上并 push 到远程

# 使用方法: ./branch_merge.sh [配置文件路径]
# 如果不提供文件路径，默认使用当前目录下的 branch_config.txt

# 配置
REMOTE_NAME="origin"
PROJECTS_ROOT="/Users/harvey/Documents/JST/projects"

# 目标分支（需要删除并重新拉取的，已改为大写）
TARGET_BRANCHES=("TEST_NORMAL" "TEST_HOTFIX" "TEMP_NORMAL")

# 额外需要更新的分支（在第一步中一并处理）
EXTRA_UPDATE_BRANCHES=("DEV_HOTFIX" "DEV_NORMAL")

# 源分支（需要更新的）
SOURCE_BRANCH="DEV_NORMAL"

# 分支配置文件路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRANCH_CONFIG_FILE="${1:-${SCRIPT_DIR}/branch_config.txt}"

# 所有项目列表
PROJECTS=(
    "dis-aftersale-service"
    "dis-common"
    "dis-company-service"
    "dis-innerorder-service"
    "dis-inner-common"
    "security-service"
)

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 精简打印函数
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

# 统计信息（使用标量变量以兼容 macOS 自带 Bash 3.2，避免 declare -A / -g）
STAT_TARGET_SUCCESS=0
STAT_TARGET_SKIP=0
STAT_TARGET_ERROR=0
STAT_MERGE_SUCCESS=0
STAT_MERGE_SKIP=0
STAT_MERGE_ERROR=0

# 错误详情记录
TARGET_BRANCH_ERRORS=()
TARGET_BRANCH_SKIPS=()
MERGE_ERRORS=()
MERGE_SKIPS=()

# 将数字代号转换为实际目标分支名
# 默认（无代号）：TEST_NORMAL,TEST_HOTFIX
# 1 = TEST_NORMAL,TEST_HOTFIX,TEMP_NORMAL
# 2 = TEST_NORMAL
# 3 = TEST_HOTFIX
# 4 = TEMP_NORMAL
# 也支持直接写分支名（如 TEST_NORMAL,TEST_HOTFIX）
resolve_target_shorthand() {
    local input="$1"
    case "$input" in
        1) echo "TEST_NORMAL,TEST_HOTFIX,TEMP_NORMAL" ;;
        2) echo "TEST_NORMAL" ;;
        3) echo "TEST_HOTFIX" ;;
        4) echo "TEMP_NORMAL" ;;
        *) echo "$input" ;;  # 非数字则原样返回
    esac
}

# 读取分支配置文件
read_branch_config() {
    local file_path="$1"
    # 与关联数组等价：键值并行数组（Bash 3.2 可用）
    BRANCH_MAP_KEYS=()
    BRANCH_MAP_VALUES=()
    BRANCHES_TO_MERGE=()
    
    if [ ! -f "$file_path" ]; then
        print_error "分支配置文件不存在: $file_path"
        return 1
    fi
    
    while IFS= read -r line || [ -n "$line" ]; do
        # 去除首尾空格
        line=$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        # 跳过空行和注释行
        if [ -n "$line" ] && [[ ! "$line" =~ ^# ]]; then
            # 检查是否包含冒号（有映射关系）
            if [[ "$line" =~ : ]]; then
                # 解析格式：分支名:目标分支或数字代号
                local branch_name=$(echo "$line" | cut -d':' -f1)
                local targets=$(echo "$line" | cut -d':' -f2)
                branch_name=$(echo "$branch_name" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
                targets=$(echo "$targets" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
                
                # 将数字代号转换为实际目标分支名
                targets=$(resolve_target_shorthand "$targets")
                
                if [ -n "$branch_name" ] && [ -n "$targets" ]; then
                    BRANCHES_TO_MERGE+=("$branch_name")
                    local _j _found=0
                    for _j in "${!BRANCH_MAP_KEYS[@]}"; do
                        if [ "${BRANCH_MAP_KEYS[$_j]}" = "$branch_name" ]; then
                            BRANCH_MAP_VALUES[$_j]="$targets"
                            _found=1
                            break
                        fi
                    done
                    if [ "$_found" -eq 0 ]; then
                        BRANCH_MAP_KEYS+=("$branch_name")
                        BRANCH_MAP_VALUES+=("$targets")
                    fi
                fi
            else
                # 只有分支名，没有映射关系
                BRANCHES_TO_MERGE+=("$line")
            fi
        fi
    done < "$file_path"
    
    return 0
}

# 获取分支应该合并到的目标分支列表
get_target_branches_for_branch() {
    local branch_name="$1"
    local targets=""
    
    local _k
    for _k in "${!BRANCH_MAP_KEYS[@]}"; do
        if [ "${BRANCH_MAP_KEYS[$_k]}" = "$branch_name" ]; then
            targets="${BRANCH_MAP_VALUES[$_k]}"
            break
        fi
    done
    
    RESULT_TARGET_BRANCHES=()
    
    if [ -z "$targets" ]; then
        # 如果没有映射，默认合并到 TEST_NORMAL 和 TEST_HOTFIX
        RESULT_TARGET_BRANCHES=("TEST_NORMAL" "TEST_HOTFIX")
    else
        # 解析映射的目标分支（用逗号分隔）
        local old_ifs="$IFS"
        IFS=','
        set -- $targets
        IFS="$old_ifs"
        for target in "$@"; do
            # 去除空格并转换为大写
            target=$(echo "$target" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | tr '[:lower:]' '[:upper:]')
            if [ -n "$target" ]; then
                RESULT_TARGET_BRANCHES+=("$target")
            fi
        done
    fi
}

# 切换到安全分支
switch_to_safe_branch() {
    local project_path="$1"
    local current_branch=$(git branch --show-current 2>/dev/null || echo "")
    
    # 检查当前分支是否在目标分支列表中
    local is_target_branch=false
    for target in "${TARGET_BRANCHES[@]}"; do
        if [ "$current_branch" = "$target" ]; then
            is_target_branch=true
            break
        fi
    done
    
    if [ "$is_target_branch" = true ] || [ "$current_branch" = "$SOURCE_BRANCH" ]; then
        # 尝试切换到 master 或 main
        if git show-ref --verify --quiet refs/heads/master; then
            git checkout master >/dev/null 2>&1 || return 1
        elif git show-ref --verify --quiet refs/heads/main; then
            git checkout main >/dev/null 2>&1 || return 1
        else
            # 尝试切换到其他分支
            local other_branch=$(git branch | grep -v "$SOURCE_BRANCH" | grep -v "^\*" | head -1 | sed 's/^[[:space:]]*//')
            for target in "${TARGET_BRANCHES[@]}"; do
                other_branch=$(echo "$other_branch" | grep -v "$target")
            done
            other_branch=$(echo "$other_branch" | head -1 | sed 's/^[[:space:]]*//')
            
            if [ -n "$other_branch" ]; then
                git checkout "$other_branch" >/dev/null 2>&1 || return 1
            else
                return 2  # 无法切换分支
            fi
        fi
    fi
    
    return 0
}

# 更新并重新拉取目标分支（需先在同一仓库内执行过 git fetch，见 step1）
update_target_branch() {
    local project_name="$1"
    local branch_name="$2"
    
    # 1. 如果本地分支存在，先删除它
    if git show-ref --verify --quiet "refs/heads/$branch_name"; then
        if git branch -D "$branch_name" >/dev/null 2>&1; then
            print_success "$project_name -> $branch_name (本地目标分支已删除)"
        else
            return 1
        fi
    fi
    
    # 2. 从远端跟踪分支创建本地分支（依赖进入本函数前已 fetch）
    if git show-ref --verify --quiet "refs/remotes/$REMOTE_NAME/$branch_name"; then
        git checkout -b "$branch_name" "$REMOTE_NAME/$branch_name" >/dev/null 2>&1 || {
            git checkout "$branch_name" >/dev/null 2>&1 || return 1
        }
        return 0
    else
        return 2  # 远端不存在
    fi
}

# 更新源分支
update_source_branch() {
    # 1. 更新远程分支信息
    git fetch $REMOTE_NAME >/dev/null 2>&1 || return 1
    
    # 2. 如果本地分支存在，切换到它并更新
    if git show-ref --verify --quiet "refs/heads/$SOURCE_BRANCH"; then
        git checkout "$SOURCE_BRANCH" >/dev/null 2>&1 || return 1
        git pull "$REMOTE_NAME" "$SOURCE_BRANCH" >/dev/null 2>&1 || {
            git fetch "$REMOTE_NAME" "$SOURCE_BRANCH:$SOURCE_BRANCH" >/dev/null 2>&1 || return 1
        }
    else
        # 3. 如果本地分支不存在，从远端创建
        if git show-ref --verify --quiet "refs/remotes/$REMOTE_NAME/$SOURCE_BRANCH"; then
            git checkout -b "$SOURCE_BRANCH" "$REMOTE_NAME/$SOURCE_BRANCH" >/dev/null 2>&1 || return 1
        else
            return 2  # 远端不存在
        fi
    fi
    
    return 0
}

# 检查分支是否存在（本地或远程）
branch_exists() {
    local branch_name="$1"
    if git show-ref --verify --quiet "refs/heads/$branch_name" || \
       git show-ref --verify --quiet "refs/remotes/$REMOTE_NAME/$branch_name"; then
        return 0
    fi
    return 1
}

# 将源分支合并到指定分支
merge_source_to_branch() {
    local target_branch="$1"
    
    # 检查目标分支是否存在
    if ! branch_exists "$target_branch"; then
        return 2  # 分支不存在
    fi
    
    # 如果本地不存在，从远端创建
    if ! git show-ref --verify --quiet "refs/heads/$target_branch"; then
        git checkout -b "$target_branch" "$REMOTE_NAME/$target_branch" >/dev/null 2>&1 || return 1
    fi
    
    # 切换到目标分支
    git checkout "$target_branch" >/dev/null 2>&1 || return 1
    
    # 合并源分支
    git merge "$SOURCE_BRANCH" --no-edit >/dev/null 2>&1 || return 1
    
    return 0
}

# 将指定分支合并到目标分支
merge_branch_to_targets() {
    local source_branch="$1"
    local target_branches=("${@:2}")
    local has_error=0
    
    # 检查源分支是否存在
    if ! branch_exists "$source_branch"; then
        return 2  # 分支不存在
    fi
    
    # 如果本地不存在，从远端创建
    if ! git show-ref --verify --quiet "refs/heads/$source_branch"; then
        git checkout -b "$source_branch" "$REMOTE_NAME/$source_branch" >/dev/null 2>&1 || return 1
    fi
    
    # 切换到源分支并更新
    git checkout "$source_branch" >/dev/null 2>&1 || return 1
    git pull "$REMOTE_NAME" "$source_branch" >/dev/null 2>&1 || true
    
    # 合并到每个目标分支并push
    for target in "${target_branches[@]}"; do
        if ! git show-ref --verify --quiet "refs/heads/$target"; then
            continue
        fi
        
        git checkout "$target" >/dev/null 2>&1 || {
            has_error=1
            continue
        }
        
        git merge "$source_branch" --no-edit >/dev/null 2>&1 || {
            has_error=1
            continue
        }
        
        # 合并成功后push到远程
        git push "$REMOTE_NAME" "$target" >/dev/null 2>&1 || {
            has_error=1
            continue
        }
    done
    
    # 如果有任何错误，返回1
    if [ $has_error -eq 1 ]; then
        return 1
    fi
    
    return 0
}

# 第一步：处理所有项目的目标分支
step1_update_target_branches() {
    echo ""
    echo "=========================================="
    print_info "第一步：更新所有项目的目标分支"
    echo "=========================================="
    
    for project in "${PROJECTS[@]}"; do
        project_path="$PROJECTS_ROOT/$project"
        
        if [ ! -d "$project_path" ]; then
            print_warning "项目目录不存在: $project"
            TARGET_BRANCH_SKIPS+=("$project - 项目目录不存在")
            ((STAT_TARGET_SKIP++))
            continue
        fi
        
        cd "$project_path" || continue
        
        if [ ! -d ".git" ]; then
            print_warning "$project 不是git仓库，跳过"
            TARGET_BRANCH_SKIPS+=("$project - 不是git仓库")
            ((STAT_TARGET_SKIP++))
            continue
        fi
        
        # 切换到安全分支
        switch_result=$(switch_to_safe_branch "$project_path" 2>&1)
        switch_code=$?
        if [ $switch_code -ne 0 ]; then
            if [ $switch_code -eq 2 ]; then
                print_error "$project - 无法切换分支（找不到可用分支）"
                TARGET_BRANCH_ERRORS+=("$project - 无法切换分支（找不到可用分支）")
            else
                print_error "$project - 切换分支失败"
                TARGET_BRANCH_ERRORS+=("$project - 切换分支失败")
            fi
            ((STAT_TARGET_ERROR++))
            continue
        fi
        
        # 每个仓库只 fetch 一次，再循环处理各目标分支
        if ! git fetch "$REMOTE_NAME" >/dev/null 2>&1; then
            print_error "$project - git fetch 失败"
            for target_branch in "${TARGET_BRANCHES[@]}"; do
                TARGET_BRANCH_ERRORS+=("$project -> $target_branch - 更新失败")
                ((STAT_TARGET_ERROR++))
            done
            continue
        fi
        
        # 处理每个目标分支
        for target_branch in "${TARGET_BRANCHES[@]}"; do
            result=$(update_target_branch "$project" "$target_branch" 2>&1)
            case $? in
                0)
                    print_success "$project -> $target_branch"
                    ((STAT_TARGET_SUCCESS++))
                    ;;
                2)
                    print_warning "$project -> $target_branch (远端不存在)"
                    TARGET_BRANCH_SKIPS+=("$project -> $target_branch - 远端不存在")
                    ((STAT_TARGET_SKIP++))
                    ;;
                *)
                    print_error "$project -> $target_branch (更新失败)"
                    TARGET_BRANCH_ERRORS+=("$project -> $target_branch - 更新失败")
                    ((STAT_TARGET_ERROR++))
                    ;;
            esac
        done
        
        # 处理额外需要更新的分支（DEV_HOTFIX、DEV_NORMAL）
        for extra_branch in "${EXTRA_UPDATE_BRANCHES[@]}"; do
            result=$(update_target_branch "$project" "$extra_branch" 2>&1)
            case $? in
                0)
                    print_success "$project -> $extra_branch"
                    ((STAT_TARGET_SUCCESS++))
                    ;;
                2)
                    print_warning "$project -> $extra_branch (远端不存在)"
                    TARGET_BRANCH_SKIPS+=("$project -> $extra_branch - 远端不存在")
                    ((STAT_TARGET_SKIP++))
                    ;;
                *)
                    print_error "$project -> $extra_branch (更新失败)"
                    TARGET_BRANCH_ERRORS+=("$project -> $extra_branch - 更新失败")
                    ((STAT_TARGET_ERROR++))
                    ;;
            esac
        done
    done
}

# 第二步：处理配置的分支合并（先按分支遍历，再按项目处理）
step2_process_merge_branches() {
    echo ""
    echo "=========================================="
    print_info "第二步：处理配置的分支合并（按分支维度遍历）"
    echo "=========================================="
    
    # 先遍历配置的分支，再遍历项目
    for branch in "${BRANCHES_TO_MERGE[@]}"; do
        echo ""
        print_info "处理分支: $branch"
        echo "----------------------------------------"
        
        # 获取该分支应该合并到的目标分支
        get_target_branches_for_branch "$branch"
        local targets=("${RESULT_TARGET_BRANCHES[@]}")
        
        if [ ${#targets[@]} -eq 0 ]; then
            print_warning "分支 $branch 没有目标分支，跳过"
            continue
        fi
        
        # 遍历所有项目
        for project in "${PROJECTS[@]}"; do
            project_path="$PROJECTS_ROOT/$project"
            
            if [ ! -d "$project_path" ]; then
                continue
            fi
            
            cd "$project_path" || continue
            
            if [ ! -d ".git" ]; then
                continue
            fi
            
            # 检查项目是否有该分支
            if ! branch_exists "$branch"; then
                print_warning "$project -> $branch (分支不存在，跳过)"
                MERGE_SKIPS+=("$project -> $branch - 分支不存在")
                ((STAT_MERGE_SKIP++))
                continue
            fi
            
            # 切换到安全分支
            switch_result=$(switch_to_safe_branch "$project_path" 2>&1)
            switch_code=$?
            if [ $switch_code -ne 0 ]; then
                if [ $switch_code -eq 2 ]; then
                    print_error "$project -> $branch - 无法切换分支（找不到可用分支）"
                    MERGE_ERRORS+=("$project -> $branch - 无法切换分支（找不到可用分支）")
                else
                    print_error "$project -> $branch - 切换分支失败"
                    MERGE_ERRORS+=("$project -> $branch - 切换分支失败")
                fi
                ((STAT_MERGE_ERROR++))
                continue
            fi
            
            # DEV_NORMAL 已在第一步中更新，直接切换到它
            if git show-ref --verify --quiet "refs/heads/$SOURCE_BRANCH"; then
                git checkout "$SOURCE_BRANCH" >/dev/null 2>&1
            else
                print_warning "$project - DEV_NORMAL (本地不存在)"
                MERGE_SKIPS+=("$project - DEV_NORMAL - 本地不存在")
            fi
            
            # 切换到安全分支
            switch_result=$(switch_to_safe_branch "$project_path" 2>&1)
            switch_code=$?
            if [ $switch_code -ne 0 ]; then
                if [ $switch_code -eq 2 ]; then
                    print_error "$project -> $branch - 无法切换分支（找不到可用分支）"
                    MERGE_ERRORS+=("$project -> $branch - 无法切换分支（找不到可用分支）")
                else
                    print_error "$project -> $branch - 切换分支失败"
                    MERGE_ERRORS+=("$project -> $branch - 切换分支失败")
                fi
                ((STAT_MERGE_ERROR++))
                continue
            fi
            
            # 将 DEV_NORMAL 合并到此分支
            result=$(merge_source_to_branch "$branch" 2>&1)
            merge_code=$?
            case $merge_code in
                0)
                    print_success "$project -> $branch (已合并 DEV_NORMAL)"
                    # 合并后把配置分支推到远程（此前只更新了本地，远程仍为合并前提交）
                    if git push "$REMOTE_NAME" "$branch" >/dev/null 2>&1; then
                        print_success "$project -> $branch (已推送到 $REMOTE_NAME)"
                    else
                        print_error "$project -> $branch (推送到远程失败，请检查权限/上游是否受保护)"
                        MERGE_ERRORS+=("$project -> $branch - 推送到远程失败")
                        ((STAT_MERGE_ERROR++))
                    fi
                    ;;
                2)
                    print_warning "$project -> $branch (分支不存在)"
                    MERGE_SKIPS+=("$project -> $branch - 分支不存在")
                    ((STAT_MERGE_SKIP++))
                    continue
                    ;;
                *)
                    print_error "$project -> $branch (合并 DEV_NORMAL 失败)"
                    MERGE_ERRORS+=("$project -> $branch - 合并 DEV_NORMAL 失败")
                    ((STAT_MERGE_ERROR++))
                    continue
                    ;;
            esac
            
            # 合并到目标分支并push
            result=$(merge_branch_to_targets "$branch" "${targets[@]}" 2>&1)
            merge_code=$?
            case $merge_code in
                0)
                    print_success "$project -> $branch -> ${targets[*]} (已合并并push)"
                    ((STAT_MERGE_SUCCESS++))
                    ;;
                2)
                    print_warning "$project -> $branch (分支不存在)"
                    MERGE_SKIPS+=("$project -> $branch - 分支不存在")
                    ((STAT_MERGE_SKIP++))
                    ;;
                *)
                    print_error "$project -> $branch -> ${targets[*]} (合并到目标分支失败)"
                    MERGE_ERRORS+=("$project -> $branch -> ${targets[*]} - 合并到目标分支失败")
                    ((STAT_MERGE_ERROR++))
                    ;;
            esac
        done
    done
}

# 主程序开始
echo "=========================================="
echo "批量更新分支并执行合并操作"
echo "=========================================="
echo "项目根目录: $PROJECTS_ROOT"
echo "配置文件: $BRANCH_CONFIG_FILE"
echo "源分支: $SOURCE_BRANCH"
echo "目标分支: ${TARGET_BRANCHES[*]}"
echo "额外更新: ${EXTRA_UPDATE_BRANCHES[*]}"
echo "=========================================="

# 读取分支配置文件
if ! read_branch_config "$BRANCH_CONFIG_FILE"; then
    print_error "无法读取分支配置文件，退出"
    exit 1
fi

if [ ${#BRANCHES_TO_MERGE[@]} -eq 0 ]; then
    print_warning "配置文件中没有找到要处理的分支"
    exit 1
fi

print_info "配置的分支数量: ${#BRANCHES_TO_MERGE[@]}"

# 执行第一步
step1_update_target_branches

# 执行第二步
step2_process_merge_branches
