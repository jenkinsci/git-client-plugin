#!/bin/bash

rm -rf unicodeCharsInChangelogRepo unicodeCharsInChangelogRepo.zip

set -e

mkdir unicodeCharsInChangelogRepo
pushd unicodeCharsInChangelogRepo
git init
git commit --allow-empty -m "Initial empty commit"

git tag v0

touch 111.txt
git add .
git commit -m "hello in English: hello"

touch 222.txt
git add .
git commit -m "hello in Russian: привет (privét)"

touch 333.txt
git add .
git commit -m "hello in Chinese: 你好 (nǐ hǎo)"

touch 444.txt
git add .
git commit -m "hello in French: Ça va ?"

touch 555.txt
git add .
git commit -m "goodbye in German: Tschüss"
git tag vLast

popd

zip -r unicodeCharsInChangelogRepo.zip unicodeCharsInChangelogRepo

rm -rf unicodeCharsInChangelogRepo
