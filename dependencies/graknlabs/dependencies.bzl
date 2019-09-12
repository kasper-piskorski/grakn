#
# GRAKN.AI - THE KNOWLEDGE GRAPH
# Copyright (C) 2019 Grakn Labs Ltd
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

def graknlabs_build_tools():
    git_repository(
        name = "graknlabs_build_tools",
        remote = "https://github.com/graknlabs/build-tools",
        commit = "8575bcdda4b8816ff7a00c05dfec1998d5a40337",  # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_build_tools
    )

def graknlabs_graql():
    #git_repository(
    #    name = "graknlabs_graql",
    #    remote = "https://github.com/graknlabs/graql",
    #    commit = "070d2fd77b71de275b6afc01ad73a725f0e2e2ab",  # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_graql
    #)
    native.local_repository(
        name = "graknlabs_graql",
        path = "/home/kasper/dev/graql",
    )

def graknlabs_common():
    git_repository(
        name = "graknlabs_common",
        remote = "https://github.com/graknlabs/common",
        commit = "b86a75b691f2b6b14b679a05dcdb1b10b57af1ed",  # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_common
    )

def graknlabs_protocol():
    git_repository(
        name = "graknlabs_protocol",
        remote = "https://github.com/graknlabs/protocol",
        commit = "bf9f8ff34f905649708dda2352f1f55985ef0f96",  # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_protocol
    )

def graknlabs_client_java():
    #git_repository(
    #    name = "graknlabs_client_java",
    #    remote = "https://github.com/graknlabs/client-java",
    #    commit = "d2c3d0c6a764e939d64005d45075c5795055084e",
    #)
    native.local_repository(
        name = "graknlabs_client_java",
        path = "/home/kasper/dev/client-java",
    )

def graknlabs_console():
    #git_repository(
    #    name = "graknlabs_console",
    #    remote = "https://github.com/graknlabs/console",
    #    commit = "cd52fdefd41f8637ad133aa5bcabf7affa169a97",
    #)
    native.local_repository(
        name = "graknlabs_console",
        path = "/home/kasper/dev/console",
    )

def graknlabs_benchmark():
    git_repository(
        name = "graknlabs_benchmark",
        remote = "https://github.com/graknlabs/benchmark.git",
        commit = "186eeabf8122c209cc7d0ab290c9fe82b2185cc8",  # keep in sync with protocol changes
    )
