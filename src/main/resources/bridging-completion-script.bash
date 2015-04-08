
# for calling from sbt
_git_sbt()
{
        local wrapper="__git_wrap${1}"
        eval "$wrapper () { __git_func_wrap $1 ; }"
        $wrapper
}

AUTO_COMPLETE_SCRIPT=$1

#. /Users/bchazalet/.git-completion.bash
. $AUTO_COMPLETE_SCRIPT

# the arguments I received should be
# COMP_WORD COMP_WORD
# for example
# 2 git co

COMP_CWORD=$2

shift 2

COMP_WORDS=(" $@ ")

_git_sbt __git_main

for item in ${COMPREPLY[*]}
do
    printf "   %s\n" $item
done 

