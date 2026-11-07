---
type: llm
---
PASS if the answer says the item is populated from the database when the block is queried, because
it maps to the CUSTOMER_NAME column of the block's base table, ORDERS.

FAIL if it only names PL/SQL or an LOV as the source, or says the value's origin can't be determined.
