#!/usr/bin/env python3
"""与 SysON 做差分：把 SysON 里某个视图的实际图形内容与我们的产物对比。

与 `diff-oracle.ps1`（对照官方 Pilot 渲染）互补：Pilot 是官方实现，SysON 是另一套
独立实现，两者都能暴露我们投影上的偏差。

典型用法（SysON 需先跑起来，本机 Docker 部署见 D:\\03-Work\\MBSE\\Syson）：

    python scripts/diff-syson.py --list-projects
    python scripts/diff-syson.py --create-project sysmlplot-diff
    python scripts/diff-syson.py --project-id <id> --import samples/structure \
        --view "StructureViews::structure" --product build/st.json --view-label structure

注意：SysON 每次 GraphQL 调用都有固定延迟（本机实测 20 秒上下），整轮跑几分钟很正常。
SysON 的搜索索引需要项目在 UI 里被打开过一次才完整，否则按标签找不到视图元素。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import uuid
from pathlib import Path

import requests
from websockets.sync.client import connect

try:  # 节点名里可能带 «…» 等符号，Windows 控制台默认 GBK 会炸
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

FETCH_EDITING_CONTEXT = """
query FetchEditingContext($projectId: ID!) {
  viewer { project(projectId: $projectId) { currentEditingContext { id } } }
}
"""

LIST_PROJECTS = """
query ListProjects {
  viewer { projects { edges { node { id name } } } }
}
"""

LIST_TEMPLATES = """
query ListProjectTemplates {
  viewer {
    projectTemplates(page: 0, limit: 50, context: null) {
      edges { node { id label } }
    }
  }
}
"""

CREATE_PROJECT = """
mutation CreateProject($input: CreateProjectInput!) {
  createProject(input: $input) {
    __typename
    ... on CreateProjectSuccessPayload { project { id name } }
    ... on ErrorPayload { messages { body level } }
  }
}
"""

UPLOAD_DOCUMENT = """
mutation UploadDocument($input: UploadDocumentInput!) {
  uploadDocument(input: $input) {
    __typename
    ... on UploadDocumentSuccessPayload { id report }
    ... on ErrorPayload { messages { body level } }
  }
}
"""

SEARCH_OBJECTS = """
query SearchObjects($editingContextId: ID!, $searchQuery: SearchQuery!) {
  viewer {
    editingContext(editingContextId: $editingContextId) {
      search(query: $searchQuery) {
        __typename
        ... on SearchSuccessPayload { result { matches { id kind label } } }
        ... on ErrorPayload { messages { body level } }
      }
    }
  }
}
"""

LIST_REPRESENTATIONS = """
query ListRepresentations($editingContextId: ID!) {
  viewer {
    editingContext(editingContextId: $editingContextId) {
      representations(first: 500) { edges { node { id label kind } } }
    }
  }
}
"""

REPRESENTATION_DESCRIPTIONS = """
query RepresentationDescriptions($editingContextId: ID!, $objectId: ID!) {
  viewer {
    editingContext(editingContextId: $editingContextId) {
      representationDescriptions(objectId: $objectId) {
        edges { node { id label defaultName } }
      }
    }
  }
}
"""

CREATE_REPRESENTATION = """
mutation CreateRepresentation($input: CreateRepresentationInput!) {
  createRepresentation(input: $input) {
    __typename
    ... on CreateRepresentationSuccessPayload { representation { id label kind } }
    ... on ErrorPayload { messages { body level } }
  }
}
"""

DIAGRAM_EVENT = """
subscription DiagramEvent($input: DiagramEventInput!) {
  diagramEvent(input: $input) {
    __typename
    ... on DiagramRefreshedEventPayload {
      diagram {
        id
        edges { id type sourceId targetId }
        nodes { ...NodeFields childNodes { ...NodeFields childNodes { ...NodeFields } } }
      }
    }
    ... on ErrorPayload { messages { body level } }
  }
}

fragment NodeFields on Node {
  id
  targetObjectId
  state
  insideLabel { text }
  outsideLabels { text }
}
"""


def graphql(url: str, query: str, variables: dict, timeout: int) -> dict:
    response = requests.post(
        f"{url.rstrip('/')}/api/graphql",
        json={"query": query, "variables": variables},
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    if payload.get("errors"):
        raise RuntimeError(json.dumps(payload["errors"], ensure_ascii=False))
    return payload["data"]


def upload_document(url: str, editing_context_id: str, path: Path, timeout: int) -> dict:
    operations = {
        "query": UPLOAD_DOCUMENT,
        "variables": {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": editing_context_id,
                "file": None,
                "readOnly": True,
            }
        },
    }
    with path.open("rb") as stream:
        response = requests.post(
            f"{url.rstrip('/')}/api/graphql/upload",
            data={
                "operations": json.dumps(operations),
                "map": json.dumps({"0": "variables.file"}),
            },
            files={"0": (path.name, stream, "text/plain")},
            timeout=timeout,
        )
    response.raise_for_status()
    payload = response.json().get("data", {}).get("uploadDocument", {})
    if payload.get("__typename") != "UploadDocumentSuccessPayload":
        raise RuntimeError(f"upload failed for {path.name}: {json.dumps(payload, ensure_ascii=False)}")
    return payload


def read_diagram(url: str, editing_context_id: str, representation_id: str, timeout: int) -> dict:
    ws_url = re.sub(r"^http", "ws", url.rstrip("/")) + "/subscriptions"
    websocket = connect(ws_url, subprotocols=["graphql-ws"], open_timeout=timeout, ping_interval=None)
    try:
        websocket.send(json.dumps({"type": "connection_init", "payload": {}}))
        while json.loads(websocket.recv(timeout=timeout)).get("type") != "connection_ack":
            pass
        websocket.send(json.dumps({
            "id": str(uuid.uuid4()),
            "type": "start",
            "payload": {
                "query": DIAGRAM_EVENT,
                "variables": {
                    "input": {
                        "id": str(uuid.uuid4()),
                        "editingContextId": editing_context_id,
                        "diagramId": representation_id,
                    }
                },
            },
        }))
        while True:
            message = json.loads(websocket.recv(timeout=timeout))
            if message.get("type") in {"ka", "connection_ack"}:
                continue
            if message.get("type") != "data":
                continue
            envelope = message.get("payload") or {}
            data = envelope.get("data")
            if data is None:
                raise RuntimeError("diagram subscription returned no data: " + json.dumps(envelope))
            event = data.get("diagramEvent", {})
            if event.get("__typename") != "DiagramRefreshedEventPayload":
                raise RuntimeError("unexpected diagram event: " + json.dumps(event, ensure_ascii=False))
            return event["diagram"]
    finally:
        try:
            websocket.close()
        except Exception:
            pass


def normalize(label: str) -> str:
    text = (label or "").strip()
    # SysON 会在标签前加构造型，如 «part» tank：去掉
    text = re.sub(r"^(\u00ab[^\u00bb]*\u00bb\s*)+", "", text)
    if text.startswith("^"):
        text = text[1:]
    text = text.replace("<U+003D>", "=")
    if ":" in text:
        text = text.split(":", 1)[0]
    return text.strip()


def flatten_nodes(nodes: list[dict]):
    for node in nodes or []:
        yield node
        yield from flatten_nodes(node.get("childNodes") or [])


def node_text(node: dict) -> str:
    inside = (node.get("insideLabel") or {}).get("text")
    if inside:
        return inside
    for outside in node.get("outsideLabels") or []:
        if outside.get("text"):
            return outside["text"]
    return ""


def our_names(product: dict) -> list[str]:
    names = []
    for node in product.get("nodes", []):
        name = node.get("name") or (node.get("ref") or "").split("::")[-1].strip("'")
        if name:
            names.append(name)
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare our view product against SysON's rendering.")
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--list-projects", action="store_true")
    parser.add_argument("--create-project")
    parser.add_argument("--template-id")
    parser.add_argument("--project-id")
    parser.add_argument("--project-name")
    parser.add_argument("--import", dest="import_dir", type=Path)
    parser.add_argument("--view", help="our view ref, used for the report")
    parser.add_argument("--product", type=Path, help="our view-product json")
    parser.add_argument("--view-label", help="label of the view usage inside SysON")
    parser.add_argument("--description", default="General View")
    args = parser.parse_args()

    if args.list_projects:
        data = graphql(args.url, LIST_PROJECTS, {}, args.timeout)
        for edge in data["viewer"]["projects"]["edges"]:
            print(f"{edge['node']['id']}  {edge['node']['name']}")
        return 0

    if args.create_project:
        template_id = args.template_id
        if not template_id:
            templates = graphql(args.url, LIST_TEMPLATES, {}, args.timeout)["viewer"]["projectTemplates"]["edges"]
            candidates = [edge["node"] for edge in templates]
            if not candidates:
                raise SystemExit("no project templates available")
            preferred = [item for item in candidates if re.search(r"sysml", item["label"], re.I)] or candidates
            template_id = preferred[0]["id"]
            print(f"[syson] using project template {preferred[0]['label']} ({template_id})")
        data = graphql(args.url, CREATE_PROJECT,
                       {"input": {"id": str(uuid.uuid4()), "name": args.create_project,
                                  "templateId": template_id}}, args.timeout)
        payload = data["createProject"]
        if payload.get("__typename") != "CreateProjectSuccessPayload":
            raise RuntimeError(json.dumps(payload, ensure_ascii=False))
        print(f"created project {payload['project']['id']}  {payload['project']['name']}")
        return 0

    project_id = args.project_id
    if not project_id and args.project_name:
        data = graphql(args.url, LIST_PROJECTS, {}, args.timeout)
        for edge in data["viewer"]["projects"]["edges"]:
            if edge["node"]["name"] == args.project_name:
                project_id = edge["node"]["id"]
                break
        if not project_id:
            raise SystemExit(f"project not found by name: {args.project_name}")
    if not project_id:
        raise SystemExit("--project-id (or --project-name) is required")

    editing_context = graphql(args.url, FETCH_EDITING_CONTEXT, {"projectId": project_id}, args.timeout)
    editing_context_id = editing_context["viewer"]["project"]["currentEditingContext"]["id"]
    print(f"[syson] project={project_id} editingContext={editing_context_id}")

    if args.import_dir:
        files = sorted(args.import_dir.rglob("*.sysml")) + sorted(args.import_dir.rglob("*.kerml"))
        if not files:
            raise SystemExit(f"no .sysml/.kerml under {args.import_dir}")
        for path in files:
            started = time.time()
            payload = upload_document(args.url, editing_context_id, path, args.timeout)
            print(f"[syson] imported {path.name} in {time.time() - started:.1f}s (report={payload.get('report')!r})")

    if not args.view_label:
        raise SystemExit("--view-label is required to locate the view usage inside SysON")

    pattern = "^(?:" + re.escape(args.view_label) + ")$"
    data = graphql(args.url, SEARCH_OBJECTS, {
        "editingContextId": editing_context_id,
        "searchQuery": {
            "text": pattern, "matchCase": True, "matchWholeWord": False,
            "useRegularExpression": True, "searchInAttributes": False, "searchInLibraries": False,
        },
    }, args.timeout)
    search = data["viewer"]["editingContext"]["search"]
    if search.get("__typename") != "SearchSuccessPayload":
        raise RuntimeError("search failed: " + json.dumps(search, ensure_ascii=False))
    matches = search["result"]["matches"]
    if not matches:
        raise SystemExit(
            f"view {args.view_label!r} not found by search. "
            "SysON 的搜索索引需要项目在 UI 里被打开过一次才完整。"
        )
    view_object_id = matches[0]["id"]
    print(f"[syson] view object {view_object_id} ({matches[0]['kind']}: {matches[0]['label']})")

    data = graphql(args.url, LIST_REPRESENTATIONS, {"editingContextId": editing_context_id}, args.timeout)
    representations = [e["node"] for e in data["viewer"]["editingContext"]["representations"]["edges"]]
    representation = next(
        (item for item in representations
         if item["label"] == args.view_label or item["label"].startswith(args.view_label)),
        None,
    )
    if representation is None:
        descriptions = graphql(args.url, REPRESENTATION_DESCRIPTIONS,
                               {"editingContextId": editing_context_id, "objectId": view_object_id},
                               args.timeout)
        nodes = descriptions["viewer"]["editingContext"]["representationDescriptions"]["edges"]
        candidates = [edge["node"] for edge in nodes if edge["node"]["label"] == args.description]
        if not candidates:
            available = ", ".join(f"{edge['node']['label']} ({edge['node']['id']})" for edge in nodes)
            raise SystemExit(f"representation description {args.description!r} not available; got: {available}")
        created = graphql(args.url, CREATE_REPRESENTATION, {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": editing_context_id,
                "objectId": view_object_id,
                "representationDescriptionId": candidates[0]["id"],
                "representationName": args.view_label,
            },
        }, args.timeout)["createRepresentation"]
        if created.get("__typename") != "CreateRepresentationSuccessPayload":
            raise RuntimeError("representation creation failed: " + json.dumps(created, ensure_ascii=False))
        representation = created["representation"]
        print(f"[syson] created representation {representation['id']} ({representation['kind']})")
    else:
        print(f"[syson] reusing representation {representation['id']} ({representation['label']})")

    diagram = read_diagram(args.url, editing_context_id, representation["id"], args.timeout)
    syson_nodes = []
    for node in flatten_nodes(diagram.get("nodes", [])):
        if node.get("state", "Normal") == "Hidden":
            continue
        text = normalize(node_text(node))
        if text and text != "noname":
            syson_nodes.append(text)
    syson_edges = len(diagram.get("edges", []))

    print(f"== {args.view or args.view_label}")
    print(f"   SysON 节点 {len(set(syson_nodes))}（去重后，共 {len(syson_nodes)} 个）/ 边 {syson_edges}")
    print(f"   SysON 节点名: {', '.join(sorted(set(syson_nodes)))}")
    if args.product:
        product = json.loads(args.product.read_text(encoding="utf-8"))
        names = our_names(product)
        missing = sorted(set(syson_nodes) - set(names))
        extra = sorted(set(names) - set(syson_nodes))
        print(f"   我方节点 {len(names)} / 边 {len(product.get('relationships', []))}")
        if missing:
            print(f"   [missing] SysON 画了、我方没有: {', '.join(missing)}")
        if extra:
            print(f"   [extra]   我方画了、SysON 没有: {', '.join(extra)}")
        if not missing and not extra:
            print("   节点集合一致")
        return 1 if missing else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
