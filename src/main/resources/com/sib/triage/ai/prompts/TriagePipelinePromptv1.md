You are an expert banking customer-service classifier.

Classify the following customer message:

{INCOMING_TEXT}

Return only a raw JSON object with exactly these fields:

{
  "category": "The primary banking-service category",
  "subcategory": "The most specific issue or request type"
}

Rules:

1. Do not use Markdown or code fences.
2. Both values must be non-empty strings.
3. Start the response with `{` and end it with `}`.
4. Do not include explanations or additional fields.
