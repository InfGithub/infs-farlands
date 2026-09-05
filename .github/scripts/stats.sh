#!/usr/bin/env bash
# Inf's Farlands 源码统计。
#
# 统计范围：src/main/java + src/client/java。
set -euo pipefail

SRC="${1:-src}"
count_annot() { # $1 = 注解名
    grep -rEn "^\s*@$1\b" "$SRC/main/java" "$SRC/client/java" --include="*.java" | wc -l
}
count_handle() { # $1 = Field|Method
    grep -rEn "\bfinal\s+$1\s+[A-Za-z_]\w*\s*;" "$SRC/main/java" "$SRC/client/java" --include="*.java" | wc -l
}

printf 'overwrite=%s\n' "$(count_annot 'Overwrite')"
printf 'inject=%s\n' "$(count_annot 'Inject')"
printf 'redirect=%s\n' "$(count_annot 'Redirect')"
printf 'modifyconstant=%s\n' "$(count_annot 'ModifyConstant')"
printf 'mixin=%s\n' "$(count_annot 'Mixin')"
printf 'field=%s\n' "$(count_handle 'Field')"
printf 'method=%s\n' "$(count_handle 'Method')"
