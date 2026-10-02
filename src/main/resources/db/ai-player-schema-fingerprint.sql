-- Stable physical identity, independent of object OIDs and generated row/sequence values.
SELECT object_key, definition FROM (
 SELECT 'column:' || c.relname || ':' || a.attname AS object_key,
        concat_ws('|', a.attnum, format_type(a.atttypid, a.atttypmod), a.attnotnull,
          a.attidentity, a.attgenerated, coalesce(pg_get_expr(d.adbin, d.adrelid), '')) AS definition
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
 LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
 WHERE n.nspname='ai_player_dev' AND c.relkind IN ('r','p','v')
 UNION ALL
 SELECT 'relation:' || c.relname, concat_ws('|', c.relkind, c.relrowsecurity, c.relforcerowsecurity)
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='ai_player_dev' AND c.relkind IN ('r','p','v','m','S')
 UNION ALL
 SELECT 'constraint:' || c.relname || ':' || k.conname,
        concat_ws('|', k.contype, k.condeferrable, k.condeferred, k.convalidated,
          pg_get_constraintdef(k.oid, false))
 FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='ai_player_dev'
 UNION ALL
 SELECT 'index:' || c.relname, concat_ws('|', i.indisvalid, i.indisready, pg_get_indexdef(c.oid))
 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='ai_player_dev'
 UNION ALL
 SELECT 'function:' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
        pg_get_functiondef(p.oid)
 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='ai_player_dev' AND p.prokind='f'
 UNION ALL
 SELECT 'trigger:' || c.relname || ':' || t.tgname,
        concat_ws('|', t.tgenabled, pg_get_triggerdef(t.oid, false))
 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='ai_player_dev' AND NOT t.tgisinternal
 UNION ALL
 SELECT 'sequence:' || c.relname,
        concat_ws('|', format_type(s.seqtypid, null), s.seqstart, s.seqincrement,
          s.seqmax, s.seqmin, s.seqcache, s.seqcycle)
 FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='ai_player_dev'
 UNION ALL
 SELECT 'view:' || c.relname, pg_get_viewdef(c.oid, false)
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='ai_player_dev' AND c.relkind IN ('v','m')
) objects ORDER BY object_key COLLATE "C", definition COLLATE "C"
