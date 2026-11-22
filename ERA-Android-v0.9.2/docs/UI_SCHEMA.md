# ERA UI snapshot schema v1

A simplified example of the JSON shape emitted by the inspector:

```json
{
  "schemaVersion": "era.ui-tree.v1",
  "capturedAtEpochMs": 1795160000000,
  "packageName": "example.app",
  "windowTitle": "Example screen",
  "nodeCount": 2,
  "truncated": false,
  "selectorGuidance": "Use resourceId/text/contentDescription/role; nodeKey is snapshot-local. Bounds are diagnostic only.",
  "root": {
    "elementId": "0",
    "nodeKey": "0",
    "parentElementId": null,
    "parentKey": null,
    "resourceId": null,
    "role": "container",
    "text": null,
    "contentDescription": null,
    "hintText": null,
    "className": "android.view.ViewGroup",
    "packageName": "example.app",
    "clickable": false,
    "longClickable": false,
    "editable": false,
    "focusable": false,
    "focused": false,
    "enabled": true,
    "disabled": false,
    "selected": false,
    "checkable": false,
    "checked": false,
    "scrollable": false,
    "password": false,
    "visible": true,
    "link": false,
    "actions": [],
    "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2200},
    "children": [
      {
        "elementId": "0.0",
        "nodeKey": "0.0",
        "parentElementId": "0",
        "parentKey": "0",
        "resourceId": "example.app:id/continue",
        "role": "button",
        "text": "Continue",
        "contentDescription": null,
        "hintText": null,
        "className": "android.widget.Button",
        "packageName": "example.app",
        "clickable": true,
        "longClickable": false,
        "editable": false,
        "focusable": true,
        "focused": false,
        "enabled": true,
        "disabled": false,
        "selected": false,
        "checkable": false,
        "checked": false,
        "scrollable": false,
        "password": false,
        "visible": true,
        "link": false,
        "actions": ["click", "focus"],
        "bounds": {"left": 20, "top": 200, "right": 400, "bottom": 260},
        "children": []
      }
    ]
  }
}
```

Example semantic selector:

```json
{
  "resourceId": "example.app:id/continue",
  "role": "button",
  "clickable": true,
  "enabled": true
}
```

Selector fields are combined with AND. If multiple elements match, ERA returns `Ambiguous` and does not choose the first match.

## Privacy

Password fields have their text, content description and hint text replaced with null. Other visible text can still contain personal information; inspect the snapshot before copying or sharing it.
