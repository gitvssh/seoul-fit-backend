-- Remove duplicate index rows produced by per-category syncs, keeping the newest row per place.
DELETE FROM poi_search_index p
USING poi_search_index q
WHERE p.ref_table = q.ref_table
  AND p.ref_id = q.ref_id
  AND p.id < q.id;

ALTER TABLE poi_search_index
  ADD CONSTRAINT uk_poi_search_index_ref UNIQUE (ref_table, ref_id);
