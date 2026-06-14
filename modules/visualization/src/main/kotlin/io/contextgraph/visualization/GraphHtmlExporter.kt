package io.contextgraph.visualization

import io.contextgraph.core.EdgeType
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter

class GraphHtmlExporter(private val storage: StorageAdapter) {
    fun export(minConfidence: Double = 0.5): String {
        val nodes = storage.getAllNodes(minConfidence)
        val edges = storage.getAllEdges(minConfidence)

        val nodesJson = nodes.joinToString(",\n", "[\n", "\n]") { n ->
            val label = n.label.replace("\"", "'").take(50)
            val type = NodeType.stringify(n.type)
            """{"id":"${n.id.value}","label":"$label","type":"$type","confidence":${n.confidence},"color":"${nodeColor(n.type)}"}"""
        }

        val edgesJson = edges.joinToString(",\n", "[\n", "\n]") { e ->
            val type = EdgeType.stringify(e.type)
            """{"from":"${e.source.value}","to":"${e.target.value}","label":"$type","confidence":${e.confidence}}"""
        }

        return HTML_TEMPLATE
            .replace("__NODES_JSON__", nodesJson)
            .replace("__EDGES_JSON__", edgesJson)
    }

    private fun nodeColor(type: NodeType): String = when (type) {
        NodeType.Function, NodeType.Method -> "#4CAF50"
        NodeType.Class -> "#2196F3"
        NodeType.Module, NodeType.Package -> "#9C27B0"
        NodeType.DatabaseTable, NodeType.Column -> "#FF9800"
        NodeType.Concept, NodeType.Claim -> "#F44336"
        NodeType.Requirement, NodeType.Decision -> "#795548"
        NodeType.Document, NodeType.MarkdownFile, NodeType.PDF, NodeType.ResearchPaper -> "#607D8B"
        NodeType.ConfigFile, NodeType.PackageFile -> "#009688"
        else -> "#9E9E9E"
    }

    companion object {
        private val HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>ContextGraph — Knowledge Graph</title>
<script src="https://unpkg.com/vis-network@9.1.9/dist/vis-network.min.js"></script>
<link rel="stylesheet" href="https://unpkg.com/vis-network@9.1.9/dist/dist/vis-network.min.css">
<style>
  body { margin: 0; font-family: sans-serif; background: #1a1a2e; color: #eee; }
  #controls { padding: 10px 20px; background: #16213e; display: flex; gap: 16px; align-items: center; }
  #controls label { font-size: 13px; }
  #search { padding: 6px 10px; border-radius: 4px; border: 1px solid #444; background: #0f3460; color: #eee; }
  #confidence-val { font-weight: bold; }
  #graph { height: calc(100vh - 110px); }
  #info { position: fixed; bottom: 0; left: 0; right: 0; background: #16213e; padding: 10px 20px; font-size: 12px; max-height: 110px; overflow-y: auto; border-top: 1px solid #444; }
</style>
</head>
<body>
<div id="controls">
  <span>ContextGraph</span>
  <input id="search" type="text" placeholder="Search nodes..." oninput="filterNodes(this.value)">
  <label>Min confidence: <input type="range" id="confidence" min="0" max="1" step="0.05" value="0.5" oninput="document.getElementById('confidence-val').textContent=this.value; filterByConfidence(parseFloat(this.value))"> <span id="confidence-val">0.5</span></label>
</div>
<div id="graph"></div>
<div id="info">Click a node to see details</div>

<script>
const allNodes = __NODES_JSON__;
const allEdges = __EDGES_JSON__;

const container = document.getElementById('graph');
const nodesDS = new vis.DataSet(allNodes.map(n => ({...n, title: n.type + '\\n' + n.label, font:{color:'#fff'}})));
const edgesDS = new vis.DataSet(allEdges.map((e,i) => ({...e, id: i, arrows:'to', color:{color:'#555'}, font:{color:'#aaa',size:10}})));

const options = {
  physics: { stabilization: { iterations: 100 }, barnesHut: { gravitationalConstant: -3000 } },
  nodes: { shape: 'dot', size: 10, borderWidth: 2, font: { size: 11 } },
  edges: { smooth: { type: 'continuous' }, width: 1 },
  interaction: { hover: true, navigationButtons: true }
};

const network = new vis.Network(container, { nodes: nodesDS, edges: edgesDS }, options);

network.on('click', function(params) {
  if (params.nodes.length) {
    const n = nodesDS.get(params.nodes[0]);
    document.getElementById('info').innerHTML = '<b>' + n.label + '</b> [' + n.type + '] id=' + n.id + ' confidence=' + n.confidence;
  }
});

function filterNodes(q) {
  const lower = q.toLowerCase();
  nodesDS.forEach(n => {
    nodesDS.update({ id: n.id, hidden: q && !n.label.toLowerCase().includes(lower) });
  });
}

function filterByConfidence(minConf) {
  nodesDS.forEach(n => {
    nodesDS.update({ id: n.id, hidden: n.confidence < minConf });
  });
  edgesDS.forEach(e => {
    edgesDS.update({ id: e.id, hidden: e.confidence < minConf });
  });
}
</script>
</body>
</html>
""".trimIndent()
    }
}
