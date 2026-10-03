-- Resolve dependent rules/triggers/defaults/policies to their owning relation.
-- pg_identify_object alone reports NULL schema for these objects, including public view rules.
-- Unknown dependent objects conservatively refuse destructive reset.
SELECT EXISTS (
    SELECT 1 FROM pg_depend d
    CROSS JOIN LATERAL pg_identify_object(d.classid,d.objid,d.objsubid) dep
    CROSS JOIN LATERAL pg_identify_object(d.refclassid,d.refobjid,d.refobjsubid) ref
    LEFT JOIN pg_rewrite rw ON d.classid='pg_rewrite'::regclass AND rw.oid=d.objid
    LEFT JOIN pg_trigger tr ON d.classid='pg_trigger'::regclass AND tr.oid=d.objid
    LEFT JOIN pg_attrdef ad ON d.classid='pg_attrdef'::regclass AND ad.oid=d.objid
    LEFT JOIN pg_constraint co ON d.classid='pg_constraint'::regclass AND co.oid=d.objid
    LEFT JOIN pg_policy po ON d.classid='pg_policy'::regclass AND po.oid=d.objid
    LEFT JOIN pg_class owner
      ON owner.oid=COALESCE(rw.ev_class,tr.tgrelid,ad.adrelid,co.conrelid,po.polrelid)
    LEFT JOIN pg_namespace ns ON ns.oid=owner.relnamespace
    WHERE ref.schema='ai_player_dev'
      AND COALESCE(dep.schema,ns.nspname,'unresolved') <> 'ai_player_dev'
      AND COALESCE(dep.schema,ns.nspname,'unresolved') NOT LIKE 'pg_%'
)
